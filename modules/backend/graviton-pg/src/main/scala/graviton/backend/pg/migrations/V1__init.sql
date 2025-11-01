-- placeholder migration for graviton postgres backend
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'replica_status') THEN
    CREATE TYPE replica_status AS ENUM ('ACTIVE', 'DEGRADED', 'OFFLINE');
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS blobs (
  key BYTEA PRIMARY KEY,
  size BIGINT NOT NULL,
  checksum BYTEA,
  content_type TEXT,
  attributes JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  version BIGINT NOT NULL DEFAULT 1,
  CONSTRAINT blobs_size_positive CHECK (size >= 0),
  CONSTRAINT blobs_checksum_length CHECK (checksum IS NULL OR octet_length(checksum) = 32),
  CONSTRAINT blobs_content_type_length CHECK (content_type IS NULL OR length(content_type) <= 255),
  CONSTRAINT blobs_attributes_is_object CHECK (attributes IS NULL OR jsonb_typeof(attributes) = 'object')
);

CREATE TABLE IF NOT EXISTS blob_data (
  key BYTEA NOT NULL REFERENCES blobs(key) ON DELETE CASCADE,
  chunk_offset BIGINT NOT NULL,
  chunk_size INT NOT NULL,
  chunk_data BYTEA NOT NULL,
  checksum BYTEA,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (key, chunk_offset),
  CONSTRAINT blob_data_chunk_offset_nonneg CHECK (chunk_offset >= 0),
  CONSTRAINT blob_data_chunk_size_positive CHECK (chunk_size > 0),
  CONSTRAINT blob_data_chunk_size_max CHECK (chunk_size <= 16777216),
  CONSTRAINT blob_data_checksum_length CHECK (checksum IS NULL OR octet_length(checksum) = 32)
);

CREATE TABLE IF NOT EXISTS manifests (
  key BYTEA PRIMARY KEY REFERENCES blobs(key) ON DELETE CASCADE,
  manifest_data BYTEA NOT NULL,
  schema_version INT NOT NULL DEFAULT 1,
  attributes JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT manifests_schema_version_positive CHECK (schema_version > 0),
  CONSTRAINT manifests_attributes_is_object CHECK (attributes IS NULL OR jsonb_typeof(attributes) = 'object')
);

CREATE TABLE IF NOT EXISTS replicas (
  key BYTEA NOT NULL REFERENCES blobs(key) ON DELETE CASCADE,
  sector_id TEXT NOT NULL,
  range_start BIGINT NOT NULL,
  range_end BIGINT NOT NULL,
  healthy BOOLEAN NOT NULL DEFAULT TRUE,
  status replica_status NOT NULL DEFAULT 'ACTIVE',
  last_verified TIMESTAMPTZ,
  PRIMARY KEY (key, sector_id, range_start),
  CONSTRAINT replicas_valid_range CHECK (range_start >= 0 AND range_end > range_start),
  CONSTRAINT replicas_sector_nonempty CHECK (char_length(sector_id) > 0)
);

CREATE TABLE IF NOT EXISTS storage_sectors (
  id TEXT PRIMARY KEY,
  region TEXT NOT NULL,
  capacity_bytes BIGINT NOT NULL,
  available_bytes BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT storage_sectors_capacity_nonneg CHECK (capacity_bytes >= 0),
  CONSTRAINT storage_sectors_available_nonneg CHECK (available_bytes >= 0 AND available_bytes <= capacity_bytes),
  CONSTRAINT storage_sectors_region_nonempty CHECK (char_length(region) > 0)
);

CREATE INDEX IF NOT EXISTS idx_blobs_created ON blobs(created_at);
CREATE INDEX IF NOT EXISTS idx_blobs_content_type ON blobs(content_type);
CREATE INDEX IF NOT EXISTS idx_blob_data_checksum ON blob_data(checksum) WHERE checksum IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_manifests_updated ON manifests(updated_at);
CREATE INDEX IF NOT EXISTS idx_replicas_sector ON replicas(sector_id);
CREATE INDEX IF NOT EXISTS idx_replicas_status ON replicas(status) WHERE status <> 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_storage_sectors_region ON storage_sectors(region);
