-- ================================================================
-- Graviton Catalog Schema (PostgreSQL 17)
-- ================================================================
CREATE EXTENSION IF NOT EXISTS pgcrypto;         -- digest(...), gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS pg_trgm;          -- trigram search on text
-- (enable pg_stat_statements in the cluster for perf insights)

-- ----------------------- Domains -------------------------------
CREATE DOMAIN hash_bytes  AS bytea CHECK (octet_length(VALUE) BETWEEN 16 AND 64);
CREATE DOMAIN small_bytes AS bytea CHECK (octet_length(VALUE) <= 1048576);   -- 1 MiB
CREATE DOMAIN store_key   AS bytea CHECK (octet_length(VALUE) = 32);         -- 256-bit digest

-- future-proof over enums (no painful ALTER TYPE ADD VALUE):
CREATE DOMAIN replica_status_t AS text
  CHECK (VALUE IN ('active','quarantined','deprecated','lost'));
CREATE DOMAIN store_status_t AS text
  CHECK (VALUE IN ('active','paused','retired'));

-- -------------------- Reference Tables -------------------------
CREATE TABLE hash_algorithm (
  id       SMALLSERIAL PRIMARY KEY,
  name     TEXT UNIQUE NOT NULL,      -- 'blake3', 'sha256', ...
  is_fips  BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE build_info (
  id            BIGSERIAL PRIMARY KEY,
  app_name      TEXT NOT NULL,
  version       TEXT NOT NULL,
  git_sha       TEXT NOT NULL,
  scala_version TEXT NOT NULL,
  zio_version   TEXT NOT NULL,
  built_at      TIMESTAMPTZ NOT NULL,
  launched_at   TIMESTAMPTZ NOT NULL,
  is_current    BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX build_info_one_current ON build_info (is_current) WHERE is_current;

-- ----------------------- Stores --------------------------------
CREATE TABLE store (
  key               store_key       PRIMARY KEY,        -- digest(impl_id || 0x00 || dv || 0x00 || build_fp)
  impl_id           TEXT            NOT NULL,           -- 's3','fs','minio','ceph',...
  build_fp          bytea           NOT NULL,           -- empty/zero if build-agnostic
  dv_schema_urn     TEXT            NOT NULL,           -- schema URI/URN for DV
  dv_canonical_bin  bytea           NOT NULL,           -- canonical DV bytes (stable codec)
  dv_json_preview   JSONB,                              -- optional pretty/debug
  status            store_status_t  NOT NULL DEFAULT 'active',
  version           BIGINT          NOT NULL DEFAULT 0, -- bumped on UPDATE by trigger
  created_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
  dv_hash           bytea GENERATED ALWAYS AS (digest(dv_canonical_bin, 'sha256')) STORED
);

-- HOT paths
CREATE INDEX store_status_idx            ON store (status);
CREATE INDEX store_status_updated_idx    ON store (status, updated_at DESC);
CREATE UNIQUE INDEX store_uniqueness     ON store (impl_id, build_fp, dv_hash);
-- searchy fields (gitops/ops browsing)
CREATE INDEX store_schema_trgm           ON store USING GIN (dv_schema_urn gin_trgm_ops);
CREATE INDEX store_impl_trgm             ON store USING GIN (impl_id gin_trgm_ops);
-- age sweeps (cheap & tiny)
CREATE INDEX store_updated_brin          ON store USING BRIN (updated_at);

-- auto version/updated_at
CREATE OR REPLACE FUNCTION store_touch() RETURNS trigger AS $$
BEGIN
  NEW.updated_at := now();
  NEW.version    := COALESCE(OLD.version, 0) + 1;
  RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER store_touch_trg
BEFORE UPDATE ON store
FOR EACH ROW EXECUTE FUNCTION store_touch();

-- ------------------------ Block (CAS) --------------------------
CREATE TABLE block (
  algo_id      SMALLINT     NOT NULL REFERENCES hash_algorithm(id),
  hash         hash_bytes   NOT NULL,
  size_bytes   BIGINT       NOT NULL,
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  inline_bytes small_bytes,                 -- optional tiny payload
  PRIMARY KEY (algo_id, hash),
  CONSTRAINT block_inline_len_consistent
    CHECK (inline_bytes IS NULL OR octet_length(inline_bytes) = size_bytes)
);
-- Optional: ensure inline_bytes is only used for small blocks
ALTER TABLE block
  ADD CONSTRAINT block_inline_threshold CHECK (inline_bytes IS NULL OR size_bytes <= 1048576);
CREATE INDEX block_size_idx   ON block (size_bytes);
CREATE INDEX block_created_br ON block USING BRIN (created_at);

-- -------------------- Replicas ---------------------------------
CREATE TABLE replica (
  id               BIGSERIAL PRIMARY KEY,
  algo_id          SMALLINT      NOT NULL,
  hash             hash_bytes    NOT NULL,
  store_key        store_key     NOT NULL REFERENCES store(key),
  sector           TEXT,
  status           replica_status_t NOT NULL DEFAULT 'active',
  size_bytes       BIGINT        NOT NULL CHECK (size_bytes > 0),
  etag             TEXT,
  storage_class    TEXT,
  first_seen_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
  last_verified_at TIMESTAMPTZ,
  UNIQUE (algo_id, hash, store_key),
  FOREIGN KEY (algo_id, hash) REFERENCES block(algo_id, hash) ON DELETE CASCADE
);
-- accelerate common probes
CREATE INDEX replica_by_block            ON replica (algo_id, hash);
CREATE INDEX replica_block_status_idx    ON replica (algo_id, hash, status);
CREATE INDEX replica_by_store_status     ON replica (store_key, status);
-- Only one "active" replica per store+block? (toggle if desired)
-- CREATE UNIQUE INDEX replica_one_active_per_store
--   ON replica (algo_id, hash, store_key)
--   WHERE status = 'active';

-- ------------------------- Blobs --------------------------------
CREATE TABLE blob (
  id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  algo_id        SMALLINT    NOT NULL REFERENCES hash_algorithm(id),
  hash           hash_bytes  NOT NULL,
  size_bytes     BIGINT      NOT NULL,
  media_type_hint TEXT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (algo_id, hash, size_bytes)
);
-- Accelerate GC/age scans
CREATE INDEX blob_created_brin ON blob USING BRIN (created_at);

CREATE TABLE manifest_entry (
  blob_id        UUID        NOT NULL REFERENCES blob(id) ON DELETE CASCADE,
  seq            INT         NOT NULL,
  block_algo_id  SMALLINT    NOT NULL,
  block_hash     hash_bytes  NOT NULL,
  offset_bytes   BIGINT      NOT NULL,
  size_bytes     BIGINT      NOT NULL CHECK (size_bytes > 0),
  PRIMARY KEY (blob_id, seq),
  FOREIGN KEY (block_algo_id, block_hash) REFERENCES block(algo_id, hash)
);
CREATE INDEX manifest_entry_by_block      ON manifest_entry (block_algo_id, block_hash);
CREATE INDEX manifest_entry_blob_seq_idx  ON manifest_entry (blob_id, seq);

-- 🍒 Sicko bit: forbid overlapping block spans per blob using an exclusion constraint
-- uses range types + GiST to ensure [offset, offset+len) segments never overlap
ALTER TABLE manifest_entry
  ADD COLUMN span int8range GENERATED ALWAYS AS (int8range(offset_bytes, offset_bytes + size_bytes, '[)')) STORED;
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE manifest_entry
  ADD CONSTRAINT manifest_entry_non_overlapping
  EXCLUDE USING gist (blob_id WITH =, span WITH &&);

-- ---------------------- Merkle Snapshots -----------------------
CREATE TABLE merkle_snapshot (
  id                 BIGSERIAL   PRIMARY KEY,
  query_fingerprint  bytea       NOT NULL,
  algo_id            SMALLINT    NOT NULL REFERENCES hash_algorithm(id),
  root_hash          hash_bytes  NOT NULL,
  at_time            TIMESTAMPTZ NOT NULL DEFAULT now(),
  note               TEXT
);
CREATE UNIQUE INDEX merkle_unique_at ON merkle_snapshot (query_fingerprint, at_time);
CREATE INDEX merkle_at_brin          ON merkle_snapshot USING BRIN (at_time);

-- ------------------- Change Notifications ----------------------
CREATE OR REPLACE FUNCTION graviton_notify_change() RETURNS trigger AS $$
DECLARE
  payload jsonb;
BEGIN
  payload := jsonb_build_object(
    'table', TG_TABLE_NAME,
    'op',    TG_OP,
    'ts',    now(),
    'row',   CASE WHEN TG_OP IN ('INSERT','UPDATE') THEN to_jsonb(NEW) ELSE to_jsonb(OLD) END
  );
  PERFORM pg_notify('graviton_inval', payload::text);
  RETURN COALESCE(NEW, OLD);
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER store_inval_trg
AFTER INSERT OR UPDATE OR DELETE ON store
FOR EACH ROW EXECUTE FUNCTION graviton_notify_change();

CREATE TRIGGER block_inval_trg
AFTER INSERT OR UPDATE OR DELETE ON block
FOR EACH ROW EXECUTE FUNCTION graviton_notify_change();

CREATE TRIGGER blob_inval_trg
AFTER INSERT OR UPDATE OR DELETE ON blob
FOR EACH ROW EXECUTE FUNCTION graviton_notify_change();

CREATE TRIGGER replica_inval_trg
AFTER INSERT OR UPDATE OR DELETE ON replica
FOR EACH ROW EXECUTE FUNCTION graviton_notify_change();

-- ------------------ (Optional) RLS Scaffolding -----------------
-- flip this on if you need per-tenant isolation later; policies are examples
-- ALTER TABLE store ENABLE ROW LEVEL SECURITY;
-- CREATE POLICY store_read_all  ON store FOR SELECT USING (true);
-- CREATE POLICY store_write_app ON store FOR INSERT WITH CHECK (current_setting('graviton.app_role', true) = 'writer');
-- CREATE POLICY store_upd_app   ON store FOR UPDATE USING (current_setting('graviton.app_role', true) = 'writer');

-- ------------------ Seed hash algorithms -----------------------
INSERT INTO hash_algorithm (name, is_fips) VALUES
  ('sha256', true),
  ('blake3', false)
ON CONFLICT DO NOTHING;
