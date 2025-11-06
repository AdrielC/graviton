SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SET check_function_bodies = off;
SET search_path = public, pg_catalog;

CREATE EXTENSION IF NOT EXISTS pgcrypto;         -- digest(...), gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS pg_trgm;          -- trigram search on text
-- (enable pg_stat_statements in the cluster for perf insights)

-- ----------------------- Domains -------------------------------
CREATE DOMAIN hash_bytes  AS bytea CHECK (octet_length(VALUE) BETWEEN 16 AND 64);
CREATE DOMAIN small_bytes AS bytea CHECK (octet_length(VALUE) <= 1048576);   -- 1 MiB
CREATE DOMAIN store_key   AS bytea CHECK (octet_length(VALUE) = 32);         -- 256-bit digest

COMMENT ON DOMAIN hash_bytes  IS 'Opaque binary hash digests with algorithm-specific widths';
COMMENT ON DOMAIN small_bytes IS 'Inline payloads that fit inside 1 MiB, typically metadata or previews';
COMMENT ON DOMAIN store_key   IS 'Stable 256-bit identity for a Store record (algo + deployment fingerprint)';

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

COMMENT ON TABLE hash_algorithm IS 'Well known hashing algorithms used across Block/Blob materialization';
COMMENT ON COLUMN hash_algorithm.id IS 'Small surrogate key referenced by block/blob rows';
COMMENT ON COLUMN hash_algorithm.name IS 'Canonical lowercase algorithm name (sha-256, blake3, md5, …)';
COMMENT ON COLUMN hash_algorithm.is_fips IS 'TRUE if the algorithm is approved for FIPS 140-3 usage';

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

COMMENT ON TABLE build_info IS 'Build metadata for the running application instances';
COMMENT ON COLUMN build_info.app_name IS 'Executable or distribution identifier (e.g. graviton-cli)';
COMMENT ON COLUMN build_info.version IS 'Semantic version string baked into the artifact';
COMMENT ON COLUMN build_info.git_sha IS 'Git commit SHA that produced the binary';
COMMENT ON COLUMN build_info.scala_version IS 'Scala compiler version used to produce this build';
COMMENT ON COLUMN build_info.zio_version IS 'Primary ZIO runtime version compiled against';
COMMENT ON COLUMN build_info.built_at IS 'UTC timestamp when the binary was built';
COMMENT ON COLUMN build_info.launched_at IS 'UTC timestamp when this build instance was launched';
COMMENT ON COLUMN build_info.is_current IS 'Flag used to pin the active build row used by health endpoints';

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

COMMENT ON TABLE store IS 'Logical blob/block store configuration and deployment fingerprint';
COMMENT ON COLUMN store.key IS 'Deterministic identifier derived from implementation + deployment version';
COMMENT ON COLUMN store.impl_id IS 'Backend implementation discriminator (fs, s3, gcs, …)';
COMMENT ON COLUMN store.build_fp IS 'Opaque binary fingerprint of the running binary or plugin build';
COMMENT ON COLUMN store.dv_schema_urn IS 'Schema URI describing the deployment vector payload';
COMMENT ON COLUMN store.dv_canonical_bin IS 'Canonical binary encoding of the deployment vector';
COMMENT ON COLUMN store.dv_json_preview IS 'Optional JSON projection used for dashboards and debugging';
COMMENT ON COLUMN store.status IS 'Operational status used by placement and repair workflows';
COMMENT ON COLUMN store.version IS 'Monotonic version incremented by trigger for optimistic locking';
COMMENT ON COLUMN store.created_at IS 'Creation timestamp for the store record';
COMMENT ON COLUMN store.updated_at IS 'Last mutation timestamp (managed by store_touch trigger)';
COMMENT ON COLUMN store.dv_hash IS 'SHA-256 digest of dv_canonical_bin to dedupe identical payloads';

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

COMMENT ON TABLE block IS 'Deduplicated binary blocks addressed by (algorithm, hash)';
COMMENT ON COLUMN block.algo_id IS 'hash_algorithm reference specifying the digest algorithm used';
COMMENT ON COLUMN block.hash IS 'Raw digest bytes for the block contents';
COMMENT ON COLUMN block.size_bytes IS 'Exact size of the block payload in bytes';
COMMENT ON COLUMN block.created_at IS 'Timestamp when the block row was inserted';
COMMENT ON COLUMN block.inline_bytes IS 'Optional inline payload for very small blocks (<= 1 MiB)';

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
CREATE INDEX replica_last_verified_active_idx ON replica (last_verified_at) WHERE status = 'active';
-- Only one "active" replica per store+block? (toggle if desired)
-- CREATE UNIQUE INDEX replica_one_active_per_store
--   ON replica (algo_id, hash, store_key)
--   WHERE status = 'active';

COMMENT ON TABLE replica IS 'Physical materializations of a block on a concrete store';
COMMENT ON COLUMN replica.algo_id IS 'hash_algorithm identifier of the associated block';
COMMENT ON COLUMN replica.hash IS 'Digest bytes referencing the associated block';
COMMENT ON COLUMN replica.store_key IS 'Foreign key to store identifying where this replica resides';
COMMENT ON COLUMN replica.sector IS 'Optional placement hint (rack/availability-zone) supplied by the store';
COMMENT ON COLUMN replica.status IS 'Operational health for the replica (active/quarantined/deprecated/lost)';
COMMENT ON COLUMN replica.size_bytes IS 'Size reported by the backend for auditing';
COMMENT ON COLUMN replica.etag IS 'Opaque backend checksum or ETag provided by the store';
COMMENT ON COLUMN replica.storage_class IS 'Backend storage-class tier (STANDARD, GLACIER, …)';
COMMENT ON COLUMN replica.first_seen_at IS 'Timestamp when this replica was first ingested';
COMMENT ON COLUMN replica.last_verified_at IS 'Last time a verification probe succeeded for this replica';

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
CREATE INDEX blob_media_type_search ON blob USING GIN (to_tsvector('simple', coalesce(media_type_hint, '')));

COMMENT ON TABLE blob IS 'High-level logical blob manifest that stitches together many blocks';
COMMENT ON COLUMN blob.id IS 'Stable UUID identifier for this blob';
COMMENT ON COLUMN blob.algo_id IS 'hash_algorithm identifier for the blob-level digest';
COMMENT ON COLUMN blob.hash IS 'Blob-level digest computed over ordered manifest bytes';
COMMENT ON COLUMN blob.size_bytes IS 'Total size of the blob represented by this manifest';
COMMENT ON COLUMN blob.media_type_hint IS 'Optional MIME type hint derived from sniffing or user metadata';
COMMENT ON COLUMN blob.created_at IS 'Timestamp when this blob manifest was recorded';

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
CREATE INDEX manifest_entry_blob_offset_idx ON manifest_entry (blob_id, offset_bytes);

-- 🍒 Sicko bit: forbid overlapping block spans per blob using an exclusion constraint
-- uses range types + GiST to ensure [offset, offset+len) segments never overlap
ALTER TABLE manifest_entry
  ADD COLUMN span int8range GENERATED ALWAYS AS (int8range(offset_bytes, offset_bytes + size_bytes, '[)')) STORED;
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE manifest_entry
  ADD CONSTRAINT manifest_entry_non_overlapping
  EXCLUDE USING gist (blob_id WITH =, span WITH &&);

COMMENT ON TABLE manifest_entry IS 'Ordered mapping from blob byte ranges to block references';
COMMENT ON COLUMN manifest_entry.blob_id IS 'Foreign key to the owning blob manifest';
COMMENT ON COLUMN manifest_entry.seq IS 'Dense 0-based sequence number describing block order';
COMMENT ON COLUMN manifest_entry.block_algo_id IS 'hash_algorithm identifier for the referenced block';
COMMENT ON COLUMN manifest_entry.block_hash IS 'Digest bytes for the referenced block';
COMMENT ON COLUMN manifest_entry.offset_bytes IS 'Starting offset of this block segment within the blob';
COMMENT ON COLUMN manifest_entry.size_bytes IS 'Size in bytes of the blob segment backed by this block';
COMMENT ON COLUMN manifest_entry.span IS 'Generated int8range used by the GiST exclusion constraint';

-- ---------------------- Merkle Snapshots -----------------------
CREATE TABLE merkle_snapshot (
  id                 BIGSERIAL   PRIMARY KEY,
  query_fingerprint  bytea       NOT NULL,
  algo_id            SMALLINT    NOT NULL REFERENCES hash_algorithm(id),
  root_hash          hash_bytes  NOT NULL,
  at_time            TIMESTAMPTZ NOT NULL DEFAULT now(),
  note               TEXT
);

COMMENT ON TABLE merkle_snapshot IS 'Materialized Merkle roots for catalog queries (for audits and proofs)';
COMMENT ON COLUMN merkle_snapshot.query_fingerprint IS 'Stable hash of the query definition feeding this snapshot';
COMMENT ON COLUMN merkle_snapshot.algo_id IS 'hash_algorithm identifier used to compute the Merkle root';
COMMENT ON COLUMN merkle_snapshot.root_hash IS 'Merkle root digest of the snapshot payload';
COMMENT ON COLUMN merkle_snapshot.at_time IS 'Collection timestamp for the Merkle snapshot';
COMMENT ON COLUMN merkle_snapshot.note IS 'Optional human readable context for the snapshot';

-- ---------------------- Helper Views -----------------------------

CREATE OR REPLACE VIEW v_store_inventory AS
SELECT
  s.key,
  s.impl_id,
  s.status,
  s.updated_at,
  COUNT(r.*)                        AS total_replicas,
  COUNT(*) FILTER (WHERE r.status = 'active')       AS active_replicas,
  COUNT(*) FILTER (WHERE r.status = 'quarantined')  AS quarantined_replicas,
  COUNT(*) FILTER (WHERE r.status = 'deprecated')   AS deprecated_replicas,
  COUNT(*) FILTER (WHERE r.status = 'lost')         AS lost_replicas,
  MIN(r.first_seen_at)              AS first_replica_seen_at,
  MAX(r.last_verified_at)           AS last_replica_verified_at
FROM store s
LEFT JOIN replica r ON r.store_key = s.key
GROUP BY s.key, s.impl_id, s.status, s.updated_at;

COMMENT ON VIEW v_store_inventory IS 'Roll-up view summarising replica distribution and health per store';

CREATE OR REPLACE VIEW v_block_replica_health AS
SELECT
  b.algo_id,
  b.hash,
  b.size_bytes,
  b.created_at,
  COUNT(r.*)                                             AS replica_count,
  COUNT(*) FILTER (WHERE r.status = 'active')            AS active_count,
  COUNT(*) FILTER (WHERE r.status = 'quarantined')       AS quarantined_count,
  COUNT(*) FILTER (WHERE r.status = 'deprecated')        AS deprecated_count,
  COUNT(*) FILTER (WHERE r.status = 'lost')              AS lost_count,
  MAX(r.last_verified_at)                                AS last_verified_at,
  bool_or(r.status = 'active')                           AS has_active,
  bool_or(r.status = 'lost')                             AS has_lost
FROM block b
LEFT JOIN replica r ON r.algo_id = b.algo_id AND r.hash = b.hash
GROUP BY b.algo_id, b.hash, b.size_bytes, b.created_at;

COMMENT ON VIEW v_block_replica_health IS 'Aggregate health signals for each block across all replicas';

CREATE OR REPLACE VIEW v_blob_manifest AS
SELECT
  bl.id,
  bl.hash,
  bl.size_bytes,
  bl.media_type_hint,
  bl.created_at,
  jsonb_agg(
    jsonb_build_object(
      'seq', me.seq,
      'offset', me.offset_bytes,
      'size', me.size_bytes,
      'algo_id', me.block_algo_id,
      'hash', encode(me.block_hash, 'hex')
    )
    ORDER BY me.seq
  ) AS manifest
FROM blob bl
LEFT JOIN manifest_entry me ON me.blob_id = bl.id
GROUP BY bl.id, bl.hash, bl.size_bytes, bl.media_type_hint, bl.created_at;

COMMENT ON VIEW v_blob_manifest IS 'Convenience JSON projection of blob manifests for APIs and debugging';

-- ---------------------- Utility Functions -----------------------

CREATE OR REPLACE FUNCTION ensure_hash_algorithm(p_name TEXT, p_is_fips BOOLEAN DEFAULT FALSE)
RETURNS SMALLINT AS $$
DECLARE
  result_id SMALLINT;
BEGIN
  INSERT INTO hash_algorithm (name, is_fips)
  VALUES (p_name, p_is_fips)
  ON CONFLICT (name) DO UPDATE SET is_fips = EXCLUDED.is_fips
  RETURNING id INTO result_id;
  RETURN result_id;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION ensure_hash_algorithm(TEXT, BOOLEAN) IS 'Idempotently create or lookup a hash_algorithm row and return its id';

CREATE OR REPLACE FUNCTION mark_replica_verified(p_replica_id BIGINT, p_verified_at TIMESTAMPTZ DEFAULT now())
RETURNS VOID AS $$
BEGIN
  UPDATE replica
     SET last_verified_at = p_verified_at,
         status = CASE WHEN status = 'lost' THEN 'quarantined' ELSE status END
   WHERE id = p_replica_id;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION mark_replica_verified(BIGINT, TIMESTAMPTZ) IS 'Update replica.last_verified_at and optionally de-escalate lost replicas back to quarantined';

CREATE OR REPLACE FUNCTION promote_store_status(p_store_key store_key, p_status store_status_t)
RETURNS VOID AS $$
BEGIN
  UPDATE store
     SET status = p_status
   WHERE key = p_store_key;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION promote_store_status(store_key, store_status_t) IS 'Bump a store record into a new status without exposing raw UPDATEs to clients';
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
SELECT ensure_hash_algorithm('sha-256', TRUE);
SELECT ensure_hash_algorithm('blake3', FALSE);
SELECT ensure_hash_algorithm('md5', FALSE);
