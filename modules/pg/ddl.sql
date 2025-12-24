-- Graviton/Quasar "authoritative" schema (alpha: major overhauls welcome)
--
-- Target: Postgres 16+.
-- Notes:
-- - Intentionally avoids PG18-only features (uuidv7, virtual-by-default generated cols).
-- - pgvector is OPTIONAL (guarded so DDL still applies if not installed).
--
-- This file is treated as source-of-truth for deployment and codegen.

SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SET check_function_bodies = off;

-- ----------------------- Extensions -----------------------------
CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid(), digest(...)
CREATE EXTENSION IF NOT EXISTS citext;     -- case-insensitive text (contrib)
CREATE EXTENSION IF NOT EXISTS pg_trgm;    -- trigram search
CREATE EXTENSION IF NOT EXISTS btree_gin;  -- optional (contrib)
CREATE EXTENSION IF NOT EXISTS btree_gist; -- exclusion constraints on composite keys (contrib)

-- pgvector is optional: don't fail schema install if the extension isn't available.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'vector') THEN
    EXECUTE 'CREATE EXTENSION IF NOT EXISTS vector';
  END IF;
END $$;

-- ----------------------- Schemas --------------------------------
CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS graviton;
CREATE SCHEMA IF NOT EXISTS quasar;

-- ----------------- Core domains + enums -------------------------
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'core' AND t.typname = 'hash_alg'
  ) THEN
    EXECUTE 'CREATE TYPE core.hash_alg AS ENUM (''sha256'', ''blake3'')';
  END IF;
END $$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'core' AND t.typname = 'byte_size'
  ) THEN
    EXECUTE 'CREATE DOMAIN core.byte_size AS bigint CHECK (VALUE >= 0)';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'core' AND t.typname = 'nonempty_text'
  ) THEN
    EXECUTE 'CREATE DOMAIN core.nonempty_text AS text CHECK (length(trim(VALUE)) > 0)';
  END IF;
END $$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'core' AND t.typname = 'lifecycle_status'
  ) THEN
    EXECUTE 'CREATE TYPE core.lifecycle_status AS ENUM (''active'',''draining'',''deprecated'',''dead'')';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'core' AND t.typname = 'present_status'
  ) THEN
    EXECUTE 'CREATE TYPE core.present_status AS ENUM (''present'',''missing'',''corrupt'',''relocating'')';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'core' AND t.typname = 'job_status'
  ) THEN
    EXECUTE 'CREATE TYPE core.job_status AS ENUM (''queued'',''leased'',''succeeded'',''failed'',''dead'')';
  END IF;
END $$;

CREATE OR REPLACE FUNCTION core.now_utc()
RETURNS timestamptz
LANGUAGE sql
STABLE
AS $$
  SELECT now();
$$;

-- generic updated_at trigger helper
CREATE OR REPLACE FUNCTION core.touch_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  NEW.updated_at := clock_timestamp();
  RETURN NEW;
END;
$$;

-- Generic change notification trigger (for cache invalidation / subscriptions).
-- Emits json payload to:
--   - channel 'graviton_inval' for graviton.*
--   - channel 'quasar_inval'   for quasar.*
CREATE OR REPLACE FUNCTION core.notify_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  payload jsonb;
  chan text;
BEGIN
  chan := CASE TG_TABLE_SCHEMA
    WHEN 'graviton' THEN 'graviton_inval'
    WHEN 'quasar' THEN 'quasar_inval'
    ELSE 'core_inval'
  END;

  payload := jsonb_build_object(
    'schema', TG_TABLE_SCHEMA,
    'table',  TG_TABLE_NAME,
    'op',     TG_OP,
    'ts',     clock_timestamp(),
    'row',    CASE WHEN TG_OP IN ('INSERT','UPDATE') THEN to_jsonb(NEW) ELSE to_jsonb(OLD) END
  );

  PERFORM pg_notify(chan, payload::text);
  RETURN COALESCE(NEW, OLD);
END;
$$;

-- ---------------- Graviton (CAS substrate) ----------------------

CREATE OR REPLACE FUNCTION graviton.is_valid_cas_key(
  alg core.hash_alg,
  hash_bytes bytea,
  byte_length bigint
)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
AS $$
  SELECT
    byte_length >= 0
    AND hash_bytes IS NOT NULL
    AND length(hash_bytes) > 0;
$$;

CREATE OR REPLACE FUNCTION graviton.cas_ref(
  alg core.hash_alg,
  hash_bytes bytea,
  byte_length bigint
)
RETURNS jsonb
LANGUAGE sql
IMMUTABLE
AS $$
  SELECT jsonb_build_object(
    'alg', alg::text,
    'hash_bytes_b64', encode(hash_bytes, 'base64'),
    'byte_length', byte_length
  );
$$;

-- 1.2 Storage topology
CREATE TABLE graviton.blob_store (
  blob_store_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  type_id       core.nonempty_text NOT NULL,     -- 's3','minio','fs','ceph',...
  config        jsonb NOT NULL,
  status        core.lifecycle_status NOT NULL DEFAULT 'active',
  created_at    timestamptz NOT NULL DEFAULT core.now_utc(),
  updated_at    timestamptz NOT NULL DEFAULT core.now_utc(),
  CONSTRAINT blob_store_config_is_object CHECK (jsonb_typeof(config) = 'object')
);
CREATE INDEX blob_store_type_status_idx ON graviton.blob_store (type_id, status);
CREATE TRIGGER blob_store_touch_trg
BEFORE UPDATE ON graviton.blob_store
FOR EACH ROW EXECUTE FUNCTION core.touch_updated_at();

CREATE TABLE graviton.sector (
  sector_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  blob_store_id uuid NOT NULL REFERENCES graviton.blob_store(blob_store_id),
  name          core.nonempty_text NOT NULL,
  priority      int NOT NULL DEFAULT 100,            -- lower = preferred for reads
  policy        jsonb NOT NULL DEFAULT '{}'::jsonb,  -- placement/replication hints
  status        core.lifecycle_status NOT NULL DEFAULT 'active',
  created_at    timestamptz NOT NULL DEFAULT core.now_utc(),
  CONSTRAINT sector_policy_is_object CHECK (jsonb_typeof(policy) = 'object'),
  UNIQUE (blob_store_id, name)
);
CREATE INDEX sector_read_pref_idx ON graviton.sector (status, priority, sector_id);

-- 1.3 Blocks (immutable chunks)
CREATE TABLE graviton.block (
  alg         core.hash_alg NOT NULL,
  hash_bytes  bytea NOT NULL,
  byte_length core.byte_size NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT core.now_utc(),
  attrs       jsonb NOT NULL DEFAULT '{}'::jsonb,
  PRIMARY KEY (alg, hash_bytes, byte_length),
  CONSTRAINT block_key_valid CHECK (graviton.is_valid_cas_key(alg, hash_bytes, byte_length)),
  CONSTRAINT block_len_positive CHECK (byte_length > 0),
  CONSTRAINT block_attrs_is_object CHECK (jsonb_typeof(attrs) = 'object')
);
CREATE INDEX block_created_idx ON graviton.block (created_at DESC);

CREATE TABLE graviton.block_location (
  block_location_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  alg         core.hash_alg NOT NULL,
  hash_bytes  bytea NOT NULL,
  byte_length core.byte_size NOT NULL,
  sector_id   uuid NOT NULL REFERENCES graviton.sector(sector_id),
  locator     jsonb NOT NULL,
  locator_canonical text GENERATED ALWAYS AS (
    coalesce(locator->>'scheme','') || '://' ||
    coalesce(locator->>'host', locator->>'bucket', '') || '/' ||
    coalesce(locator->>'key', locator->>'path', '')
  ) STORED,
  stored_length core.byte_size NOT NULL,
  frame_format  int NOT NULL DEFAULT 1,
  encryption    jsonb NOT NULL DEFAULT '{}'::jsonb,
  status        core.present_status NOT NULL DEFAULT 'present',
  written_at    timestamptz NOT NULL DEFAULT core.now_utc(),
  verified_at   timestamptz NULL,
  FOREIGN KEY (alg, hash_bytes, byte_length)
    REFERENCES graviton.block(alg, hash_bytes, byte_length),
  CONSTRAINT locator_is_object CHECK (jsonb_typeof(locator) = 'object'),
  CONSTRAINT encryption_is_object CHECK (jsonb_typeof(encryption) = 'object'),
  CONSTRAINT locator_has_scheme CHECK (locator ? 'scheme'),
  CONSTRAINT locator_has_keyish CHECK ((locator ? 'key') OR (locator ? 'path')),
  CONSTRAINT locator_scheme_format CHECK ((locator->>'scheme') ~ '^[a-z][a-z0-9+.-]*$'),
  CONSTRAINT locator_scheme_contract CHECK (
    CASE locator->>'scheme'
      WHEN 's3' THEN (locator ? 'bucket') AND (locator ? 'key')
      WHEN 'fs' THEN (locator ? 'path')
      WHEN 'ceph' THEN (locator ? 'pool') AND (locator ? 'key')
      ELSE true
    END
  ),
  CONSTRAINT stored_length_nonneg CHECK (stored_length >= 0)
);
CREATE INDEX block_location_lookup_idx
  ON graviton.block_location (alg, hash_bytes, byte_length, status, sector_id)
  INCLUDE (stored_length, verified_at, written_at);
CREATE INDEX block_location_locator_gin
  ON graviton.block_location USING gin (locator jsonb_path_ops);
CREATE INDEX block_location_sector_status_idx
  ON graviton.block_location (sector_id, status);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'graviton' AND t.typname = 'verify_result'
  ) THEN
    EXECUTE 'CREATE TYPE graviton.verify_result AS ENUM (''ok'',''missing'',''hash_mismatch'',''decrypt_fail'',''other'')';
  END IF;
END $$;

CREATE TABLE graviton.block_verify_event (
  event_id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  alg               core.hash_alg NOT NULL,
  hash_bytes        bytea NOT NULL,
  byte_length       core.byte_size NOT NULL,
  block_location_id uuid NULL REFERENCES graviton.block_location(block_location_id),
  result            graviton.verify_result NOT NULL,
  details           jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at        timestamptz NOT NULL DEFAULT core.now_utc(),
  FOREIGN KEY (alg, hash_bytes, byte_length)
    REFERENCES graviton.block(alg, hash_bytes, byte_length),
  CONSTRAINT details_is_object CHECK (jsonb_typeof(details) = 'object')
);
CREATE INDEX block_verify_event_block_idx
  ON graviton.block_verify_event (alg, hash_bytes, byte_length, created_at DESC);

-- 1.4 Blobs + manifests
CREATE TABLE graviton.blob (
  alg         core.hash_alg NOT NULL,
  hash_bytes  bytea NOT NULL,
  byte_length core.byte_size NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT core.now_utc(),
  block_count int NOT NULL,
  chunker     jsonb NOT NULL DEFAULT '{}'::jsonb,
  attrs       jsonb NOT NULL DEFAULT '{}'::jsonb,
  PRIMARY KEY (alg, hash_bytes, byte_length),
  CONSTRAINT blob_key_valid CHECK (graviton.is_valid_cas_key(alg, hash_bytes, byte_length)),
  CONSTRAINT blob_block_count_nonneg CHECK (block_count >= 0),
  CONSTRAINT chunker_is_object CHECK (jsonb_typeof(chunker) = 'object'),
  CONSTRAINT attrs_is_object CHECK (jsonb_typeof(attrs) = 'object')
);
CREATE INDEX blob_created_idx ON graviton.blob (created_at DESC);

CREATE TABLE graviton.blob_manifest_page (
  alg         core.hash_alg NOT NULL,
  hash_bytes  bytea NOT NULL,
  byte_length core.byte_size NOT NULL,
  page_no     int NOT NULL,
  entry_count int NOT NULL,
  entries     bytea NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT core.now_utc(),
  PRIMARY KEY (alg, hash_bytes, byte_length, page_no),
  FOREIGN KEY (alg, hash_bytes, byte_length)
    REFERENCES graviton.blob(alg, hash_bytes, byte_length),
  CONSTRAINT page_no_nonneg CHECK (page_no >= 0),
  CONSTRAINT entry_count_nonneg CHECK (entry_count >= 0)
);
CREATE INDEX blob_manifest_page_read_idx
  ON graviton.blob_manifest_page (alg, hash_bytes, byte_length, page_no);

-- Optional relational manifest (SQL introspection + repair tooling)
CREATE TABLE graviton.blob_block (
  alg             core.hash_alg NOT NULL,
  hash_bytes      bytea NOT NULL,
  byte_length     core.byte_size NOT NULL,
  ordinal         int NOT NULL,
  block_alg       core.hash_alg NOT NULL,
  block_hash_bytes bytea NOT NULL,
  block_byte_length core.byte_size NOT NULL,
  block_offset    core.byte_size NOT NULL,
  block_length    core.byte_size NOT NULL,
  span int8range GENERATED ALWAYS AS (
    int8range(block_offset, block_offset + block_length, '[)')
  ) STORED,
  PRIMARY KEY (alg, hash_bytes, byte_length, ordinal),
  FOREIGN KEY (alg, hash_bytes, byte_length)
    REFERENCES graviton.blob(alg, hash_bytes, byte_length),
  FOREIGN KEY (block_alg, block_hash_bytes, block_byte_length)
    REFERENCES graviton.block(alg, hash_bytes, byte_length),
  CONSTRAINT ordinal_nonneg CHECK (ordinal >= 0),
  CONSTRAINT offsets_valid CHECK (block_offset >= 0 AND block_length > 0)
);
CREATE INDEX blob_block_lookup_idx
  ON graviton.blob_block (alg, hash_bytes, byte_length, ordinal)
  INCLUDE (block_alg, block_hash_bytes, block_byte_length, block_offset, block_length);
ALTER TABLE graviton.blob_block
  ADD CONSTRAINT blob_block_non_overlapping
  EXCLUDE USING gist (alg WITH =, hash_bytes WITH =, byte_length WITH =, span WITH &&);

-- 1.5 Views + transforms (DAG)
CREATE TABLE graviton.transform (
  transform_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name         core.nonempty_text NOT NULL,
  version      core.nonempty_text NOT NULL,
  arg_schema   jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at   timestamptz NOT NULL DEFAULT core.now_utc(),
  UNIQUE (name, version),
  CONSTRAINT arg_schema_is_object CHECK (jsonb_typeof(arg_schema) = 'object')
);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'graviton' AND t.typname = 'view_status'
  ) THEN
    EXECUTE 'CREATE TYPE graviton.view_status AS ENUM (''virtual'',''materialized'',''failed'')';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'graviton' AND t.typname = 'input_kind'
  ) THEN
    EXECUTE 'CREATE TYPE graviton.input_kind AS ENUM (''blob'',''view'')';
  END IF;
END $$;

CREATE TABLE graviton.view (
  view_id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  canonical_key  bytea NOT NULL UNIQUE,
  status         graviton.view_status NOT NULL DEFAULT 'virtual',
  created_at     timestamptz NOT NULL DEFAULT core.now_utc()
);

CREATE TABLE graviton.view_input (
  view_id     uuid NOT NULL REFERENCES graviton.view(view_id) ON DELETE CASCADE,
  ordinal     int NOT NULL,
  input_kind  graviton.input_kind NOT NULL,
  input_ref   jsonb NOT NULL,
  PRIMARY KEY (view_id, ordinal),
  CONSTRAINT ordinal_nonneg CHECK (ordinal >= 0),
  CONSTRAINT input_ref_is_object CHECK (jsonb_typeof(input_ref) = 'object')
);

CREATE TABLE graviton.view_op (
  view_id       uuid NOT NULL REFERENCES graviton.view(view_id) ON DELETE CASCADE,
  ordinal       int NOT NULL,
  transform_id  uuid NOT NULL REFERENCES graviton.transform(transform_id),
  args          jsonb NOT NULL DEFAULT '{}'::jsonb,
  PRIMARY KEY (view_id, ordinal),
  CONSTRAINT ordinal_nonneg CHECK (ordinal >= 0),
  CONSTRAINT args_is_object CHECK (jsonb_typeof(args) = 'object')
);

CREATE TABLE graviton.view_materialization (
  view_id uuid PRIMARY KEY REFERENCES graviton.view(view_id) ON DELETE CASCADE,
  result_alg core.hash_alg NOT NULL,
  result_hash_bytes bytea NOT NULL,
  result_byte_length core.byte_size NOT NULL,
  materialized_at timestamptz NOT NULL DEFAULT core.now_utc(),
  cache_status core.lifecycle_status NOT NULL DEFAULT 'active',
  FOREIGN KEY (result_alg, result_hash_bytes, result_byte_length)
    REFERENCES graviton.blob(alg, hash_bytes, byte_length)
);

-- ---------------- Quasar (application substrate) ----------------

-- Tenancy roots (not partitioned)
CREATE TABLE quasar.tenant (
  tenant_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name      core.nonempty_text NOT NULL UNIQUE,
  status    core.lifecycle_status NOT NULL DEFAULT 'active',
  created_at timestamptz NOT NULL DEFAULT core.now_utc()
);

CREATE TABLE quasar.org (
  org_id    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES quasar.tenant(tenant_id),
  name      core.nonempty_text NOT NULL,
  status    core.lifecycle_status NOT NULL DEFAULT 'active',
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  UNIQUE (tenant_id, name)
);

-- Principals (partitioned by org_id)
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'quasar' AND t.typname = 'principal_kind'
  ) THEN
    EXECUTE 'CREATE TYPE quasar.principal_kind AS ENUM (''user'',''group'',''service'')';
  END IF;
END $$;

CREATE TABLE quasar.principal (
  org_id       uuid NOT NULL REFERENCES quasar.org(org_id),
  principal_id uuid NOT NULL DEFAULT gen_random_uuid(),
  kind         quasar.principal_kind NOT NULL,
  display_name text NOT NULL,
  status       core.lifecycle_status NOT NULL DEFAULT 'active',
  created_at   timestamptz NOT NULL DEFAULT core.now_utc(),
  PRIMARY KEY (org_id, principal_id)
) PARTITION BY HASH (org_id);

DO $$
BEGIN
  FOR i IN 0..15 LOOP
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS quasar.principal_p%1$s PARTITION OF quasar.principal FOR VALUES WITH (MODULUS 16, REMAINDER %1$s)',
      i
    );
  END LOOP;
END $$;

CREATE INDEX principal_org_kind_idx ON quasar.principal (org_id, kind, status);

CREATE TABLE quasar.principal_external_identity (
  org_id       uuid NOT NULL,
  principal_id uuid NOT NULL,
  issuer       core.nonempty_text NOT NULL,
  subject      core.nonempty_text NOT NULL,
  claims       jsonb NOT NULL DEFAULT '{}'::jsonb,
  last_seen_at timestamptz NOT NULL DEFAULT core.now_utc(),
  PRIMARY KEY (org_id, principal_id, issuer, subject),
  UNIQUE (org_id, issuer, subject),
  FOREIGN KEY (org_id, principal_id)
    REFERENCES quasar.principal(org_id, principal_id)
    ON DELETE CASCADE,
  CONSTRAINT claims_is_object CHECK (jsonb_typeof(claims) = 'object')
) PARTITION BY HASH (org_id);

DO $$
BEGIN
  FOR i IN 0..15 LOOP
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS quasar.principal_external_identity_p%1$s PARTITION OF quasar.principal_external_identity FOR VALUES WITH (MODULUS 16, REMAINDER %1$s)',
      i
    );
  END LOOP;
END $$;

-- Upload staging (partitioned by org_id)
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'quasar' AND t.typname = 'upload_status'
  ) THEN
    EXECUTE 'CREATE TYPE quasar.upload_status AS ENUM (''open'',''uploading'',''sealed'',''expired'',''aborted'',''finalized'')';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'quasar' AND t.typname = 'upload_file_status'
  ) THEN
    EXECUTE 'CREATE TYPE quasar.upload_file_status AS ENUM (''uploading'',''complete'',''failed'')';
  END IF;
END $$;

CREATE TABLE quasar.upload_session (
  org_id uuid NOT NULL REFERENCES quasar.org(org_id),
  upload_session_id uuid NOT NULL DEFAULT gen_random_uuid(),
  created_by_principal_id uuid NOT NULL,
  status quasar.upload_status NOT NULL DEFAULT 'open',
  expires_at timestamptz NOT NULL,
  constraints jsonb NOT NULL DEFAULT '{}'::jsonb,
  client_context jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  PRIMARY KEY (org_id, upload_session_id),
  FOREIGN KEY (org_id, created_by_principal_id)
    REFERENCES quasar.principal(org_id, principal_id),
  CONSTRAINT constraints_is_object CHECK (jsonb_typeof(constraints) = 'object'),
  CONSTRAINT client_context_is_object CHECK (jsonb_typeof(client_context) = 'object')
) PARTITION BY HASH (org_id);

DO $$
BEGIN
  FOR i IN 0..15 LOOP
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS quasar.upload_session_p%1$s PARTITION OF quasar.upload_session FOR VALUES WITH (MODULUS 16, REMAINDER %1$s)',
      i
    );
  END LOOP;
END $$;

CREATE TABLE quasar.upload_file (
  org_id uuid NOT NULL,
  upload_file_id uuid NOT NULL DEFAULT gen_random_uuid(),
  upload_session_id uuid NOT NULL,
  client_file_name text NOT NULL,
  declared_size core.byte_size NULL,
  declared_hash jsonb NULL,
  status quasar.upload_file_status NOT NULL DEFAULT 'uploading',
  result_blob_ref jsonb NULL,
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  PRIMARY KEY (org_id, upload_file_id),
  FOREIGN KEY (org_id, upload_session_id)
    REFERENCES quasar.upload_session(org_id, upload_session_id)
    ON DELETE CASCADE,
  CONSTRAINT declared_hash_is_object CHECK (declared_hash IS NULL OR jsonb_typeof(declared_hash) = 'object'),
  CONSTRAINT result_blob_ref_is_object CHECK (result_blob_ref IS NULL OR jsonb_typeof(result_blob_ref) = 'object')
) PARTITION BY HASH (org_id);

DO $$
BEGIN
  FOR i IN 0..15 LOOP
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS quasar.upload_file_p%1$s PARTITION OF quasar.upload_file FOR VALUES WITH (MODULUS 16, REMAINDER %1$s)',
      i
    );
  END LOOP;
END $$;

CREATE INDEX upload_file_session_idx ON quasar.upload_file (org_id, upload_session_id);

CREATE TABLE quasar.upload_part (
  org_id uuid NOT NULL,
  upload_file_id uuid NOT NULL,
  part_no int NOT NULL,
  byte_range int8range NOT NULL,
  etag text NULL,
  status core.present_status NOT NULL DEFAULT 'present',
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  PRIMARY KEY (org_id, upload_file_id, part_no),
  FOREIGN KEY (org_id, upload_file_id)
    REFERENCES quasar.upload_file(org_id, upload_file_id)
    ON DELETE CASCADE,
  CONSTRAINT nonempty_range CHECK (lower(byte_range) < upper(byte_range))
) PARTITION BY HASH (org_id);

DO $$
BEGIN
  FOR i IN 0..15 LOOP
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS quasar.upload_part_p%1$s PARTITION OF quasar.upload_part FOR VALUES WITH (MODULUS 16, REMAINDER %1$s)',
      i
    );
  END LOOP;
END $$;

CREATE INDEX upload_part_range_gist ON quasar.upload_part USING gist (upload_file_id, byte_range);

-- Documents + read model (partitioned by org_id)
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'quasar' AND t.typname = 'doc_status'
  ) THEN
    EXECUTE 'CREATE TYPE quasar.doc_status AS ENUM (''draft'',''active'',''deleted'')';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'quasar' AND t.typname = 'content_kind'
  ) THEN
    EXECUTE 'CREATE TYPE quasar.content_kind AS ENUM (''blob'',''view'')';
  END IF;
END $$;

CREATE TABLE quasar.document (
  org_id uuid NOT NULL REFERENCES quasar.org(org_id),
  doc_id uuid NOT NULL DEFAULT gen_random_uuid(),
  created_by_principal_id uuid NOT NULL,
  title text NOT NULL,
  status quasar.doc_status NOT NULL DEFAULT 'draft',
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  PRIMARY KEY (org_id, doc_id),
  FOREIGN KEY (org_id, created_by_principal_id)
    REFERENCES quasar.principal(org_id, principal_id)
) PARTITION BY HASH (org_id);

DO $$
BEGIN
  FOR i IN 0..15 LOOP
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS quasar.document_p%1$s PARTITION OF quasar.document FOR VALUES WITH (MODULUS 16, REMAINDER %1$s)',
      i
    );
  END LOOP;
END $$;

CREATE TABLE quasar.document_version (
  org_id uuid NOT NULL,
  doc_version_id uuid NOT NULL DEFAULT gen_random_uuid(),
  doc_id uuid NOT NULL,
  version int NOT NULL,
  created_by_principal_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  content_kind quasar.content_kind NOT NULL,
  content_ref jsonb NOT NULL,
  PRIMARY KEY (org_id, doc_version_id),
  UNIQUE (org_id, doc_id, version),
  FOREIGN KEY (org_id, doc_id)
    REFERENCES quasar.document(org_id, doc_id)
    ON DELETE CASCADE,
  FOREIGN KEY (org_id, created_by_principal_id)
    REFERENCES quasar.principal(org_id, principal_id),
  CONSTRAINT content_ref_is_object CHECK (jsonb_typeof(content_ref) = 'object')
) PARTITION BY HASH (org_id);

DO $$
BEGIN
  FOR i IN 0..15 LOOP
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS quasar.document_version_p%1$s PARTITION OF quasar.document_version FOR VALUES WITH (MODULUS 16, REMAINDER %1$s)',
      i
    );
  END LOOP;
END $$;

CREATE INDEX document_version_doc_idx ON quasar.document_version (org_id, doc_id, version DESC);

CREATE TABLE quasar.document_current (
  org_id uuid NOT NULL,
  doc_id uuid NOT NULL,
  current_version_id uuid NOT NULL,
  content_kind quasar.content_kind NOT NULL,
  content_ref jsonb NOT NULL,
  status quasar.doc_status NOT NULL,
  title text NOT NULL,
  last_modified_at timestamptz NOT NULL DEFAULT core.now_utc(),
  PRIMARY KEY (org_id, doc_id),
  FOREIGN KEY (org_id, doc_id)
    REFERENCES quasar.document(org_id, doc_id)
    ON DELETE CASCADE,
  FOREIGN KEY (org_id, current_version_id)
    REFERENCES quasar.document_version(org_id, doc_version_id),
  CONSTRAINT content_ref_is_object CHECK (jsonb_typeof(content_ref) = 'object')
) PARTITION BY HASH (org_id);

DO $$
BEGIN
  FOR i IN 0..15 LOOP
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS quasar.document_current_p%1$s PARTITION OF quasar.document_current FOR VALUES WITH (MODULUS 16, REMAINDER %1$s)',
      i
    );
  END LOOP;
END $$;

CREATE INDEX document_current_org_status_idx ON quasar.document_current (org_id, status, last_modified_at DESC);

CREATE TABLE quasar.document_alias (
  org_id uuid NOT NULL REFERENCES quasar.org(org_id),
  system core.nonempty_text NOT NULL,
  external_id text NOT NULL,
  doc_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  PRIMARY KEY (org_id, system, external_id),
  FOREIGN KEY (org_id, doc_id)
    REFERENCES quasar.document(org_id, doc_id)
    ON DELETE CASCADE
) PARTITION BY HASH (org_id);

DO $$
BEGIN
  FOR i IN 0..15 LOOP
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS quasar.document_alias_p%1$s PARTITION OF quasar.document_alias FOR VALUES WITH (MODULUS 16, REMAINDER %1$s)',
      i
    );
  END LOOP;
END $$;

CREATE INDEX document_alias_doc_idx ON quasar.document_alias (org_id, doc_id);

-- Namespaces + schema registry
CREATE TABLE quasar.namespace (
  org_id uuid NOT NULL REFERENCES quasar.org(org_id),
  namespace_id uuid NOT NULL DEFAULT gen_random_uuid(),
  urn core.nonempty_text NOT NULL,
  status core.lifecycle_status NOT NULL DEFAULT 'active',
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  PRIMARY KEY (org_id, namespace_id),
  UNIQUE (org_id, urn)
) PARTITION BY HASH (org_id);

DO $$
BEGIN
  FOR i IN 0..15 LOOP
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS quasar.namespace_p%1$s PARTITION OF quasar.namespace FOR VALUES WITH (MODULUS 16, REMAINDER %1$s)',
      i
    );
  END LOOP;
END $$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'quasar' AND t.typname = 'schema_status'
  ) THEN
    EXECUTE 'CREATE TYPE quasar.schema_status AS ENUM (''draft'',''active'',''deprecated'',''revoked'')';
  END IF;
END $$;

-- Not partitioned: org_id may be NULL (global schemas), and we want global uniqueness for canonical_hash.
CREATE TABLE quasar.schema_registry (
  schema_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NULL REFERENCES quasar.org(org_id),  -- NULL = global schema
  schema_urn core.nonempty_text NOT NULL,
  schema_json jsonb NOT NULL,
  canonical_hash bytea NOT NULL UNIQUE,
  status quasar.schema_status NOT NULL DEFAULT 'draft',
  supersedes_schema_id uuid NULL REFERENCES quasar.schema_registry(schema_id),
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  CONSTRAINT schema_json_is_object CHECK (jsonb_typeof(schema_json) = 'object')
);
CREATE INDEX schema_registry_lookup_idx ON quasar.schema_registry (schema_urn, status, created_at DESC);

CREATE TABLE quasar.document_namespace (
  org_id uuid NOT NULL,
  doc_version_id uuid NOT NULL,
  namespace_id uuid NOT NULL,
  schema_id uuid NULL REFERENCES quasar.schema_registry(schema_id),
  data jsonb NOT NULL,
  is_valid boolean NOT NULL DEFAULT true,
  validation_errors jsonb NOT NULL DEFAULT '[]'::jsonb,
  PRIMARY KEY (org_id, doc_version_id, namespace_id),
  FOREIGN KEY (org_id, doc_version_id)
    REFERENCES quasar.document_version(org_id, doc_version_id)
    ON DELETE CASCADE,
  FOREIGN KEY (org_id, namespace_id)
    REFERENCES quasar.namespace(org_id, namespace_id),
  CONSTRAINT data_is_object_or_array CHECK (jsonb_typeof(data) IN ('object','array')),
  CONSTRAINT validation_errors_is_array CHECK (jsonb_typeof(validation_errors) = 'array')
) PARTITION BY HASH (org_id);

DO $$
BEGIN
  FOR i IN 0..15 LOOP
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS quasar.document_namespace_p%1$s PARTITION OF quasar.document_namespace FOR VALUES WITH (MODULUS 16, REMAINDER %1$s)',
      i
    );
  END LOOP;
END $$;

CREATE INDEX doc_namespace_data_gin ON quasar.document_namespace USING gin (data jsonb_path_ops);

-- JSON extraction view (PG16-safe; JSON_TABLE is PG17+).
CREATE OR REPLACE VIEW quasar.v_doc_upload_claims AS
SELECT
  dn.org_id,
  dn.doc_version_id,
  dn.namespace_id,
  (dn.data #>> '{claimedLength}')::bigint AS claimed_size,
  dn.data #>> '{mediaType}'              AS media_type,
  dn.data #>> '{fileName}'               AS file_name
FROM quasar.document_namespace dn;

-- Permissions
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'quasar' AND t.typname = 'resource_kind'
  ) THEN
    EXECUTE 'CREATE TYPE quasar.resource_kind AS ENUM (''document'',''folder'',''namespace'',''schema'')';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'quasar' AND t.typname = 'effect'
  ) THEN
    EXECUTE 'CREATE TYPE quasar.effect AS ENUM (''allow'',''deny'')';
  END IF;
END $$;

CREATE TABLE quasar.acl_entry (
  org_id uuid NOT NULL REFERENCES quasar.org(org_id),
  acl_entry_id uuid NOT NULL DEFAULT gen_random_uuid(),
  resource_kind quasar.resource_kind NOT NULL,
  resource_id uuid NOT NULL,
  principal_id uuid NOT NULL,
  effect quasar.effect NOT NULL,
  capabilities bigint NOT NULL,
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  PRIMARY KEY (org_id, acl_entry_id),
  FOREIGN KEY (org_id, principal_id)
    REFERENCES quasar.principal(org_id, principal_id)
) PARTITION BY HASH (org_id);

DO $$
BEGIN
  FOR i IN 0..15 LOOP
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS quasar.acl_entry_p%1$s PARTITION OF quasar.acl_entry FOR VALUES WITH (MODULUS 16, REMAINDER %1$s)',
      i
    );
  END LOOP;
END $$;

CREATE INDEX acl_resource_idx ON quasar.acl_entry (org_id, resource_kind, resource_id);
CREATE INDEX acl_principal_idx ON quasar.acl_entry (org_id, principal_id);

CREATE TABLE quasar.policy (
  org_id uuid NOT NULL REFERENCES quasar.org(org_id),
  policy_id uuid NOT NULL DEFAULT gen_random_uuid(),
  name core.nonempty_text NOT NULL,
  ast jsonb NOT NULL,
  status core.lifecycle_status NOT NULL DEFAULT 'active',
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  PRIMARY KEY (org_id, policy_id),
  CONSTRAINT ast_is_object CHECK (jsonb_typeof(ast) = 'object'),
  UNIQUE (org_id, name)
) PARTITION BY HASH (org_id);

DO $$
BEGIN
  FOR i IN 0..15 LOOP
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS quasar.policy_p%1$s PARTITION OF quasar.policy FOR VALUES WITH (MODULUS 16, REMAINDER %1$s)',
      i
    );
  END LOOP;
END $$;

CREATE INDEX policy_org_status_idx ON quasar.policy (org_id, status);

-- Semantic search (optional pgvector)
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'quasar' AND t.typname = 'embedding_status'
  ) THEN
    EXECUTE 'CREATE TYPE quasar.embedding_status AS ENUM (''pending'',''ready'',''stale'',''failed'')';
  END IF;
END $$;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'vector') THEN
    EXECUTE $sql$
      CREATE TABLE IF NOT EXISTS quasar.document_embedding (
        org_id uuid NOT NULL REFERENCES quasar.org(org_id),
        doc_id uuid NOT NULL,
        current_version_id uuid NOT NULL,
        model core.nonempty_text NOT NULL,
        dims int NOT NULL,
        embedding vector NOT NULL,
        status quasar.embedding_status NOT NULL DEFAULT 'pending',
        updated_at timestamptz NOT NULL DEFAULT core.now_utc(),
        PRIMARY KEY (org_id, doc_id),
        FOREIGN KEY (org_id, doc_id)
          REFERENCES quasar.document(org_id, doc_id)
          ON DELETE CASCADE,
        CONSTRAINT dims_positive CHECK (dims > 0)
      );
    $sql$;
  END IF;
END $$;

-- Jobs / outbox (partitioned by org_id; dedupe_key is tenant-scoped)
CREATE TABLE quasar.outbox_job (
  org_id uuid NOT NULL REFERENCES quasar.org(org_id),
  job_id uuid NOT NULL DEFAULT gen_random_uuid(),
  kind core.nonempty_text NOT NULL,
  dedupe_key core.nonempty_text NOT NULL,
  payload jsonb NOT NULL,
  status core.job_status NOT NULL DEFAULT 'queued',
  lease_owner text NULL,
  lease_expires_at timestamptz NULL,
  attempts int NOT NULL DEFAULT 0,
  next_run_at timestamptz NOT NULL DEFAULT core.now_utc(),
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  PRIMARY KEY (org_id, job_id),
  UNIQUE (org_id, dedupe_key),
  CONSTRAINT payload_is_object CHECK (jsonb_typeof(payload) = 'object')
) PARTITION BY HASH (org_id);

DO $$
BEGIN
  FOR i IN 0..15 LOOP
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS quasar.outbox_job_p%1$s PARTITION OF quasar.outbox_job FOR VALUES WITH (MODULUS 16, REMAINDER %1$s)',
      i
    );
  END LOOP;
END $$;

CREATE INDEX outbox_runnable_idx ON quasar.outbox_job (org_id, status, next_run_at)
  WHERE status IN ('queued','leased');
CREATE INDEX outbox_lease_idx ON quasar.outbox_job (org_id, lease_owner, lease_expires_at);

-- ----------------------- RLS scaffolding ------------------------
CREATE OR REPLACE FUNCTION quasar.current_org_id()
RETURNS uuid
LANGUAGE sql
STABLE
AS $$
  SELECT nullif(current_setting('app.org_id', true), '')::uuid;
$$;

-- ---------------------------- RLS --------------------------------
-- App is expected to set:
--   SET LOCAL app.org_id = '<uuid>';
--
-- "Full sicko": let Postgres enforce org isolation.

ALTER TABLE quasar.principal ENABLE ROW LEVEL SECURITY;
CREATE POLICY quasar_principal_org_isolation
  ON quasar.principal
  USING (org_id = quasar.current_org_id());

ALTER TABLE quasar.principal_external_identity ENABLE ROW LEVEL SECURITY;
CREATE POLICY quasar_principal_ext_org_isolation
  ON quasar.principal_external_identity
  USING (org_id = quasar.current_org_id());

ALTER TABLE quasar.upload_session ENABLE ROW LEVEL SECURITY;
CREATE POLICY quasar_upload_session_org_isolation
  ON quasar.upload_session
  USING (org_id = quasar.current_org_id());

ALTER TABLE quasar.upload_file ENABLE ROW LEVEL SECURITY;
CREATE POLICY quasar_upload_file_org_isolation
  ON quasar.upload_file
  USING (org_id = quasar.current_org_id());

ALTER TABLE quasar.upload_part ENABLE ROW LEVEL SECURITY;
CREATE POLICY quasar_upload_part_org_isolation
  ON quasar.upload_part
  USING (org_id = quasar.current_org_id());

ALTER TABLE quasar.document ENABLE ROW LEVEL SECURITY;
CREATE POLICY quasar_document_org_isolation
  ON quasar.document
  USING (org_id = quasar.current_org_id());

ALTER TABLE quasar.document_version ENABLE ROW LEVEL SECURITY;
CREATE POLICY quasar_document_version_org_isolation
  ON quasar.document_version
  USING (org_id = quasar.current_org_id());

ALTER TABLE quasar.document_current ENABLE ROW LEVEL SECURITY;
CREATE POLICY quasar_document_current_org_isolation
  ON quasar.document_current
  USING (org_id = quasar.current_org_id());

ALTER TABLE quasar.document_alias ENABLE ROW LEVEL SECURITY;
CREATE POLICY quasar_document_alias_org_isolation
  ON quasar.document_alias
  USING (org_id = quasar.current_org_id());

ALTER TABLE quasar.namespace ENABLE ROW LEVEL SECURITY;
CREATE POLICY quasar_namespace_org_isolation
  ON quasar.namespace
  USING (org_id = quasar.current_org_id());

ALTER TABLE quasar.document_namespace ENABLE ROW LEVEL SECURITY;
CREATE POLICY quasar_document_namespace_org_isolation
  ON quasar.document_namespace
  USING (org_id = quasar.current_org_id());

ALTER TABLE quasar.acl_entry ENABLE ROW LEVEL SECURITY;
CREATE POLICY quasar_acl_entry_org_isolation
  ON quasar.acl_entry
  USING (org_id = quasar.current_org_id());

ALTER TABLE quasar.policy ENABLE ROW LEVEL SECURITY;
CREATE POLICY quasar_policy_org_isolation
  ON quasar.policy
  USING (org_id = quasar.current_org_id());

ALTER TABLE quasar.outbox_job ENABLE ROW LEVEL SECURITY;
CREATE POLICY quasar_outbox_job_org_isolation
  ON quasar.outbox_job
  USING (org_id = quasar.current_org_id());

-- Schema registry is special: org_id NULL means "global schema"
ALTER TABLE quasar.schema_registry ENABLE ROW LEVEL SECURITY;
CREATE POLICY quasar_schema_registry_visible
  ON quasar.schema_registry
  USING (org_id IS NULL OR org_id = quasar.current_org_id());

-- Optional: if vector table exists, protect it too.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'quasar' AND c.relname = 'document_embedding'
  ) THEN
    EXECUTE 'ALTER TABLE quasar.document_embedding ENABLE ROW LEVEL SECURITY';
    EXECUTE $pol$
      CREATE POLICY quasar_document_embedding_org_isolation
        ON quasar.document_embedding
        USING (org_id = quasar.current_org_id())
    $pol$;
  END IF;
END $$;

-- ----------------------- Change notifications -------------------
-- Keep triggers small: focus on metadata + hot-path tables.

CREATE TRIGGER graviton_blob_store_inval_trg
AFTER INSERT OR UPDATE OR DELETE ON graviton.blob_store
FOR EACH ROW EXECUTE FUNCTION core.notify_change();

CREATE TRIGGER graviton_sector_inval_trg
AFTER INSERT OR UPDATE OR DELETE ON graviton.sector
FOR EACH ROW EXECUTE FUNCTION core.notify_change();

CREATE TRIGGER graviton_block_location_inval_trg
AFTER INSERT OR UPDATE OR DELETE ON graviton.block_location
FOR EACH ROW EXECUTE FUNCTION core.notify_change();

CREATE TRIGGER graviton_blob_manifest_page_inval_trg
AFTER INSERT OR UPDATE OR DELETE ON graviton.blob_manifest_page
FOR EACH ROW EXECUTE FUNCTION core.notify_change();

CREATE TRIGGER graviton_blob_block_inval_trg
AFTER INSERT OR UPDATE OR DELETE ON graviton.blob_block
FOR EACH ROW EXECUTE FUNCTION core.notify_change();

CREATE TRIGGER quasar_document_current_inval_trg
AFTER INSERT OR UPDATE OR DELETE ON quasar.document_current
FOR EACH ROW EXECUTE FUNCTION core.notify_change();

CREATE TRIGGER quasar_outbox_job_inval_trg
AFTER INSERT OR UPDATE OR DELETE ON quasar.outbox_job
FOR EACH ROW EXECUTE FUNCTION core.notify_change();

-- ----------------------------------------------------------------
-- Hot path helpers (resolution primitives)
-- ----------------------------------------------------------------

-- Pick best physical candidates for a logical block.
--
-- Ordering:
--   1) sector priority (lower is better)
--   2) freshest verification (NULLS LAST)
--   3) most recently written
CREATE OR REPLACE FUNCTION graviton.best_block_locations(
  p_alg core.hash_alg,
  p_hash_bytes bytea,
  p_byte_length bigint,
  p_limit int DEFAULT 5
)
RETURNS TABLE (
  sector_priority int,
  sector_id uuid,
  blob_store_id uuid,
  blob_store_type_id text,
  block_location_id uuid,
  status core.present_status,
  locator jsonb,
  locator_canonical text,
  stored_length bigint,
  frame_format int,
  encryption jsonb,
  written_at timestamptz,
  verified_at timestamptz
)
LANGUAGE sql
STABLE
AS $$
  SELECT
    s.priority AS sector_priority,
    s.sector_id,
    s.blob_store_id,
    bs.type_id AS blob_store_type_id,
    bl.block_location_id,
    bl.status,
    bl.locator,
    bl.locator_canonical,
    bl.stored_length,
    bl.frame_format,
    bl.encryption,
    bl.written_at,
    bl.verified_at
  FROM graviton.block_location bl
  JOIN graviton.sector s
    ON s.sector_id = bl.sector_id
  JOIN graviton.blob_store bs
    ON bs.blob_store_id = s.blob_store_id
  WHERE bl.alg = p_alg
    AND bl.hash_bytes = p_hash_bytes
    AND bl.byte_length = p_byte_length
    AND bl.status = 'present'
    AND s.status = 'active'
    AND bs.status = 'active'
  ORDER BY
    s.priority ASC,
    bl.verified_at DESC NULLS LAST,
    bl.written_at DESC
  LIMIT GREATEST(p_limit, 0);
$$;

-- Convenience: best single location per block key.
CREATE OR REPLACE VIEW graviton.v_best_block_location AS
SELECT DISTINCT ON (bl.alg, bl.hash_bytes, bl.byte_length)
  bl.alg,
  bl.hash_bytes,
  bl.byte_length,
  s.priority AS sector_priority,
  bl.sector_id,
  s.blob_store_id,
  bl.block_location_id,
  bl.status,
  bl.locator,
  bl.locator_canonical,
  bl.stored_length,
  bl.frame_format,
  bl.encryption,
  bl.written_at,
  bl.verified_at
FROM graviton.block_location bl
JOIN graviton.sector s
  ON s.sector_id = bl.sector_id
JOIN graviton.blob_store bs
  ON bs.blob_store_id = s.blob_store_id
WHERE bl.status = 'present'
  AND s.status = 'active'
  AND bs.status = 'active'
ORDER BY
  bl.alg,
  bl.hash_bytes,
  bl.byte_length,
  s.priority ASC,
  bl.verified_at DESC NULLS LAST,
  bl.written_at DESC;

-- Stream manifest pages for a blob in order (paged manifest hot path).
CREATE OR REPLACE FUNCTION graviton.manifest_pages(
  p_alg core.hash_alg,
  p_hash_bytes bytea,
  p_byte_length bigint
)
RETURNS TABLE (
  page_no int,
  entry_count int,
  entries bytea
)
LANGUAGE sql
STABLE
AS $$
  SELECT
    p.page_no,
    p.entry_count,
    p.entries
  FROM graviton.blob_manifest_page p
  WHERE p.alg = p_alg
    AND p.hash_bytes = p_hash_bytes
    AND p.byte_length = p_byte_length
  ORDER BY p.page_no ASC;
$$;

-- Full "blob → ordered block spans → best location" plan in one query.
-- Intended for repair tooling and for building a streaming plan in the app layer.
CREATE OR REPLACE FUNCTION graviton.resolve_blob_read_plan(
  p_alg core.hash_alg,
  p_hash_bytes bytea,
  p_byte_length bigint
)
RETURNS TABLE (
  ordinal int,
  block_alg core.hash_alg,
  block_hash_bytes bytea,
  block_byte_length bigint,
  block_offset bigint,
  block_length bigint,
  sector_priority int,
  sector_id uuid,
  blob_store_id uuid,
  blob_store_type_id text,
  locator jsonb,
  locator_canonical text,
  stored_length bigint,
  verified_at timestamptz
)
LANGUAGE sql
STABLE
AS $$
  SELECT
    bb.ordinal,
    bb.block_alg,
    bb.block_hash_bytes,
    bb.block_byte_length,
    bb.block_offset,
    bb.block_length,
    cand.sector_priority,
    cand.sector_id,
    cand.blob_store_id,
    cand.blob_store_type_id,
    cand.locator,
    cand.locator_canonical,
    cand.stored_length,
    cand.verified_at
  FROM graviton.blob_block bb
  LEFT JOIN LATERAL graviton.best_block_locations(
    bb.block_alg,
    bb.block_hash_bytes,
    bb.block_byte_length,
    1
  ) cand ON true
  WHERE bb.alg = p_alg
    AND bb.hash_bytes = p_hash_bytes
    AND bb.byte_length = p_byte_length
  ORDER BY bb.ordinal ASC;
$$;
