-- ---- Domains / enums
CREATE DOMAIN hash_bytes AS bytea CHECK (octet_length(VALUE) BETWEEN 16 AND 64);
CREATE DOMAIN small_bytes AS bytea CHECK (octet_length(VALUE) <= 1048576); -- 1 MiB
CREATE DOMAIN store_key AS bytea CHECK (octet_length(VALUE) = 32); -- 256-bit digest

CREATE TYPE location_status AS ENUM ('active','stale','missing','deprecated','error');
CREATE TYPE store_status    AS ENUM ('active','paused','retired');

-- ---- Hash algorithms
CREATE TABLE hash_algorithm (
  id       SMALLSERIAL PRIMARY KEY,
  name     TEXT UNIQUE NOT NULL,          -- 'blake3', 'sha256', ...
  is_fips  BOOLEAN NOT NULL DEFAULT FALSE
);

-- ---- Build info (current runtime; helps couple store keys to a build, if desired)
CREATE TABLE build_info (
  id            BIGSERIAL PRIMARY KEY,
  app_name      TEXT NOT NULL,
  version       TEXT NOT NULL,
  git_sha       TEXT NOT NULL,            -- or digest bytes if you prefer
  scala_version TEXT NOT NULL,
  zio_version   TEXT NOT NULL,
  built_at      TIMESTAMPTZ NOT NULL,
  launched_at   TIMESTAMPTZ NOT NULL,
  is_current    BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX build_info_one_current ON build_info (is_current) WHERE is_current;

-- ---- BlobStore registry, keyed by canonical config
CREATE TABLE blob_store (
  key               store_key PRIMARY KEY,  -- 32 bytes: digest(impl_id || 0x00 || dv || 0x00 || build_fp)
  impl_id           TEXT NOT NULL,          -- 's3','fs','ceph','rados','minio', ...
  build_fp          bytea NOT NULL,         -- empty/zero if build-agnostic
  dv_schema_urn     TEXT NOT NULL,          -- URI/URN for the Schema used to encode DV
  dv_canonical_bin  bytea NOT NULL,         -- canonical DV bytes (stable codec)
  dv_json_preview   JSONB,                  -- optional pretty/debug
  status            store_status NOT NULL DEFAULT 'active',
  version           BIGINT NOT NULL DEFAULT 0, -- bump to invalidate pool/caches
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX blob_store_status_idx ON blob_store (status);
CREATE UNIQUE INDEX blob_store_uniqueness ON blob_store (impl_id, build_fp, dv_canonical_bin);

-- ---- Block (CAS unit)
CREATE TABLE block (
  algo_id      SMALLINT NOT NULL REFERENCES hash_algorithm(id),
  hash         hash_bytes NOT NULL,      -- raw digest only
  size_bytes   BIGINT NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  inline_bytes small_bytes,              -- optional tiny payload
  PRIMARY KEY (algo_id, hash)
);
CREATE INDEX block_size_idx ON block (size_bytes);

-- ---- Block physical locations (many stores per block)
CREATE TABLE block_location (
  id                 BIGSERIAL PRIMARY KEY,
  algo_id            SMALLINT NOT NULL,
  hash               hash_bytes NOT NULL,
  blob_store_key     store_key NOT NULL REFERENCES blob_store(key),
  uri                TEXT,
  status             location_status NOT NULL DEFAULT 'active',
  bytes_length       BIGINT NOT NULL,
  etag               TEXT,
  storage_class      TEXT,
  first_seen_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_verified_at   TIMESTAMPTZ,
  UNIQUE (algo_id, hash, blob_store_key),
  FOREIGN KEY (algo_id, hash) REFERENCES block(algo_id, hash) ON DELETE CASCADE
);
CREATE INDEX block_location_by_store_status ON block_location (blob_store_key, status);
CREATE INDEX block_location_by_block       ON block_location (algo_id, hash);

-- ---- File (ordered list of blocks, with its own digest)
CREATE TABLE file (
  id          UUID PRIMARY KEY,
  algo_id     SMALLINT NOT NULL REFERENCES hash_algorithm(id),
  hash        hash_bytes NOT NULL,
  size_bytes  BIGINT NOT NULL,
  media_type  TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (algo_id, hash, size_bytes)
);

CREATE TABLE file_block (
  file_id        UUID NOT NULL REFERENCES file(id) ON DELETE CASCADE,
  seq            INT  NOT NULL,
  block_algo_id  SMALLINT NOT NULL,
  block_hash     hash_bytes NOT NULL,
  offset_bytes   BIGINT NOT NULL,
  length_bytes   BIGINT NOT NULL,
  PRIMARY KEY (file_id, seq),
  FOREIGN KEY (block_algo_id, block_hash) REFERENCES block(algo_id, hash)
);
CREATE INDEX file_block_by_block ON file_block (block_algo_id, block_hash);

-- ---- Merkle snapshots for sync/diff of Graviton slices (no Quasar)
CREATE TABLE merkle_snapshot (
  id                 BIGSERIAL PRIMARY KEY,
  query_fingerprint  bytea NOT NULL,
  algo_id            SMALLINT NOT NULL REFERENCES hash_algorithm(id),
  root_hash          hash_bytes NOT NULL,
  at_time            TIMESTAMPTZ NOT NULL DEFAULT now(),
  note               TEXT
);
CREATE UNIQUE INDEX merkle_unique_at ON merkle_snapshot (query_fingerprint, at_time);

-- ---- LISTEN/NOTIFY invalidation for caches/pools
CREATE OR REPLACE FUNCTION graviton_notify_change() RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify('graviton_inval', json_build_object(
    'table', TG_TABLE_NAME, 'op', TG_OP, 'ts', now()
  )::text);
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER blob_store_inval_trg
AFTER INSERT OR UPDATE OR DELETE ON blob_store FOR EACH ROW
EXECUTE FUNCTION graviton_notify_change();

CREATE TRIGGER block_inval_trg
AFTER INSERT OR UPDATE OR DELETE ON block FOR EACH ROW
EXECUTE FUNCTION graviton_notify_change();

CREATE TRIGGER file_inval_trg
AFTER INSERT OR UPDATE OR DELETE ON file FOR EACH ROW
EXECUTE FUNCTION graviton_notify_change();

