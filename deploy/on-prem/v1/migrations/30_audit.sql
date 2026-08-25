-- Append-only audit log with a per-org hash chain for tamper evidence.
-- Runs after 10_schema.sql + 20_grants.sql (lexicographic init order).

\connect quasar

-- pgcrypto provides digest() used by verify_audit_chain below.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- CAS audit and ACL resources use the same enum as document resources.
ALTER TYPE quasar.resource_kind ADD VALUE IF NOT EXISTS 'blob';

-- ---------------------------------------------------------------
-- audit_outcome enum
-- ---------------------------------------------------------------
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'quasar' AND t.typname = 'audit_outcome'
  ) THEN
    EXECUTE 'CREATE TYPE quasar.audit_outcome AS ENUM (''allow'',''deny'',''error'',''auth_fail'')';
  END IF;
END $$;

-- ---------------------------------------------------------------
-- audit_log: partitioned by org_id, RLS-enforced, INSERT-only for app.
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS quasar.audit_log (
  org_id        uuid                  NOT NULL REFERENCES quasar.org(org_id),
  seq           bigint                NOT NULL,
  ts            timestamptz           NOT NULL DEFAULT core.now_utc(),
  principal_id  uuid                  NOT NULL,
  action        core.nonempty_text    NOT NULL,   -- e.g. 'blob.read'
  resource_kind quasar.resource_kind  NOT NULL,
  resource_id   uuid                  NULL,
  request_id    uuid                  NOT NULL,
  source_ip     inet                  NULL,
  user_agent    text                  NULL,
  outcome       quasar.audit_outcome  NOT NULL,
  reason        text                  NULL,
  bytes         bigint                NULL,
  prev_hash     bytea                 NOT NULL,
  row_hash      bytea                 NOT NULL,
  PRIMARY KEY (org_id, seq),
  CONSTRAINT row_hash_len CHECK (octet_length(row_hash)  = 32),
  CONSTRAINT prev_hash_len CHECK (octet_length(prev_hash) = 32)
) PARTITION BY HASH (org_id);

DO $$
BEGIN
  FOR i IN 0..15 LOOP
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS quasar.audit_log_p%1$s PARTITION OF quasar.audit_log FOR VALUES WITH (MODULUS 16, REMAINDER %1$s)',
      i
    );
  END LOOP;
END $$;

CREATE INDEX IF NOT EXISTS audit_log_ts_idx
  ON quasar.audit_log (org_id, ts DESC);

CREATE INDEX IF NOT EXISTS audit_log_principal_idx
  ON quasar.audit_log (org_id, principal_id, ts DESC);

CREATE INDEX IF NOT EXISTS audit_log_resource_idx
  ON quasar.audit_log (org_id, resource_kind, resource_id)
  WHERE resource_id IS NOT NULL;

-- ---------------------------------------------------------------
-- RLS: every read / write scoped to the current org.
-- ---------------------------------------------------------------
ALTER TABLE quasar.audit_log ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'quasar' AND tablename = 'audit_log' AND policyname = 'quasar_audit_log_org_isolation'
  ) THEN
    EXECUTE $pol$
      CREATE POLICY quasar_audit_log_org_isolation
        ON quasar.audit_log
        USING (org_id = quasar.current_org_id())
        WITH CHECK (org_id = quasar.current_org_id())
    $pol$;
  END IF;
END $$;

-- ---------------------------------------------------------------
-- Revoke UPDATE / DELETE from the app role: append-only semantics.
-- (quasar_migrate retains full DDL control for retention policies.)
-- ---------------------------------------------------------------
REVOKE UPDATE, DELETE, TRUNCATE ON quasar.audit_log FROM quasar_app;
GRANT  SELECT, INSERT                ON quasar.audit_log TO   quasar_app;

-- ---------------------------------------------------------------
-- current_principal_id helper for potential row-level audits.
-- ---------------------------------------------------------------
CREATE OR REPLACE FUNCTION quasar.current_principal_id()
RETURNS uuid
LANGUAGE sql
STABLE
AS $$
  SELECT nullif(current_setting('app.principal_id', true), '')::uuid;
$$;

-- ---------------------------------------------------------------
-- Chain-linkage check (cheap): confirms prev_hash(N) = row_hash(N-1).
-- Full recomputation (bytes of canonical payload = row_hash) is done by
-- a Scala verifier that reads rows back; the canonical-byte format is
-- defined in graviton.security.AuditSink to avoid cross-language drift.
-- ---------------------------------------------------------------
CREATE OR REPLACE FUNCTION quasar.verify_audit_chain_linkage(p_org uuid)
RETURNS TABLE(ok boolean, broken_at bigint, total bigint)
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
  rec          record;
  expected     bytea := decode('0000000000000000000000000000000000000000000000000000000000000000', 'hex');
  bad_seq      bigint := NULL;
  count_rows   bigint := 0;
BEGIN
  FOR rec IN
    SELECT seq, prev_hash, row_hash
    FROM quasar.audit_log
    WHERE org_id = p_org
    ORDER BY seq
  LOOP
    count_rows := count_rows + 1;
    IF rec.prev_hash <> expected THEN
      bad_seq := rec.seq;
      EXIT;
    END IF;
    expected := rec.row_hash;
  END LOOP;

  RETURN QUERY SELECT (bad_seq IS NULL), bad_seq, count_rows;
END;
$$;
