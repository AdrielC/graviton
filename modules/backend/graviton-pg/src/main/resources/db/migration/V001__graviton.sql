-- Graviton migration V001: authoritative byte-substrate schema
--
-- Target: PostgreSQL 16+.
-- Notes:
-- - This migration contains only tables and functions owned by the Graviton
--   byte, identity, integrity, security, upload, and maintenance substrate.
-- - Document models, transforms, views, extraction, and search are not part of
--   this schema.
--
-- This file is treated as source-of-truth for deployment and codegen.

SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SET check_function_bodies = off;

-- ----------------------- Extensions -----------------------------
CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS btree_gist; -- exclusion constraints on CAS ranges

-- ----------------------- Schemas --------------------------------
CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS graviton;

-- Transaction-local tenant identity used by row-level security. Define it
-- before any tenant table policy references it.
CREATE OR REPLACE FUNCTION graviton.current_org_id()
RETURNS uuid
LANGUAGE sql
STABLE
AS $$
  SELECT nullif(current_setting('app.org_id', true), '')::uuid;
$$;

-- Shardcake placement state. The manager lease rejects overlapping managers,
-- while transaction advisory locks serialize complete assignment and pod
-- replacements. Upload bytes and session hot state never live here.
CREATE TABLE IF NOT EXISTS graviton.shardcake_assignment (
  shard_id integer PRIMARY KEY CHECK (shard_id >= 1),
  pod_host varchar(120) NULL,
  pod_port integer NULL CHECK (pod_port BETWEEN 1 AND 65535),
  CONSTRAINT shardcake_assignment_owner_pair CHECK (
    (pod_host IS NULL AND pod_port IS NULL) OR
    (pod_host IS NOT NULL AND pod_port IS NOT NULL)
  )
);

CREATE TABLE IF NOT EXISTS graviton.shardcake_pod (
  pod_host varchar(120) NOT NULL,
  pod_port integer NOT NULL CHECK (pod_port BETWEEN 1 AND 65535),
  server_version varchar(64) NOT NULL CHECK (length(server_version) >= 1),
  PRIMARY KEY (pod_host, pod_port)
);

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

CREATE OR REPLACE FUNCTION core.now_utc()
RETURNS timestamptz
LANGUAGE sql
STABLE
AS $$
  SELECT now();
$$;

-- Server-owned organization routing policy. Authenticated callers map to
-- tenant_id and cannot select deduplication domains or admission limits.
-- NULL deduplication_domain means a physically isolated storage namespace.
CREATE TABLE IF NOT EXISTS graviton.tenant_storage_policy (
  tenant_id uuid PRIMARY KEY,
  cell_id varchar(120) NOT NULL DEFAULT 'default' CHECK (cell_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$'),
  lifecycle text NOT NULL DEFAULT 'active' CHECK (lifecycle IN ('active', 'suspended')),
  deduplication_domain varchar(120) NULL CHECK (
    deduplication_domain IS NULL OR deduplication_domain ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$'
  ),
  max_concurrent_operations integer NOT NULL DEFAULT 32 CHECK (max_concurrent_operations BETWEEN 1 AND 65535),
  max_object_bytes core.byte_size NOT NULL DEFAULT 1099511627776 CHECK (max_object_bytes BETWEEN 1 AND 1099511627776),
  max_retained_bytes core.byte_size NOT NULL DEFAULT 1125899906842624 CHECK (max_retained_bytes BETWEEN 1 AND 9223372036854775807),
  revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  updated_at timestamptz NOT NULL DEFAULT core.now_utc()
);
CREATE INDEX IF NOT EXISTS tenant_storage_policy_cell_idx
  ON graviton.tenant_storage_policy (cell_id, tenant_id);

-- Cluster-atomic logical usage. Writers lock this row in the same transaction
-- that publishes or deletes a tenant manifest, so independent upload nodes
-- cannot collectively exceed a tenant's retained-byte policy.
CREATE TABLE IF NOT EXISTS graviton.tenant_storage_usage (
  tenant_id uuid PRIMARY KEY REFERENCES graviton.tenant_storage_policy(tenant_id) ON DELETE CASCADE,
  retained_bytes core.byte_size NOT NULL DEFAULT 0,
  blob_count bigint NOT NULL DEFAULT 0 CHECK (blob_count >= 0),
  updated_at timestamptz NOT NULL DEFAULT core.now_utc()
);

-- Immutable, transactionally captured membership used by domain-wide scrub
-- and GC. A maintenance cycle is tied to one snapshot so policy changes cannot
-- silently add or remove manifest repositories midway through the mark phase.
CREATE TABLE IF NOT EXISTS graviton.tenant_domain_snapshot (
  snapshot_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  cell_id varchar(120) NOT NULL CHECK (cell_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$'),
  captured_at timestamptz NOT NULL DEFAULT core.now_utc(),
  member_count bigint NOT NULL CHECK (member_count >= 0),
  membership_sha256 bytea NOT NULL CHECK (octet_length(membership_sha256) = 32)
);
CREATE INDEX IF NOT EXISTS tenant_domain_snapshot_cell_idx
  ON graviton.tenant_domain_snapshot (cell_id, captured_at DESC, snapshot_id DESC);

CREATE TABLE IF NOT EXISTS graviton.tenant_domain_snapshot_member (
  snapshot_id uuid NOT NULL REFERENCES graviton.tenant_domain_snapshot(snapshot_id) ON DELETE CASCADE,
  storage_domain_id varchar(128) NOT NULL CHECK (storage_domain_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
  tenant_id uuid NOT NULL,
  policy_revision bigint NOT NULL CHECK (policy_revision >= 0),
  PRIMARY KEY (snapshot_id, storage_domain_id, tenant_id)
);
CREATE INDEX IF NOT EXISTS tenant_domain_snapshot_member_domain_idx
  ON graviton.tenant_domain_snapshot_member (snapshot_id, storage_domain_id, tenant_id);

-- Library-level replica catalog for logical replicas exposed by ReplicaIndex.
CREATE TABLE IF NOT EXISTS graviton.replica_index (
  key_kind   text NOT NULL CHECK (key_kind IN ('blob', 'block', 'chunk', 'manifest', 'view')),
  alg        core.hash_alg NOT NULL,
  hash_bytes bytea NOT NULL,
  byte_length core.byte_size NOT NULL,
  locator    text NOT NULL CHECK (locator ~ '^[a-z][a-z0-9+.-]*://'),
  updated_at timestamptz NOT NULL DEFAULT core.now_utc(),
  PRIMARY KEY (key_kind, alg, hash_bytes, byte_length, locator),
  CHECK (key_kind NOT IN ('blob', 'block') OR byte_length > 0)
);
CREATE INDEX IF NOT EXISTS replica_index_key_idx
  ON graviton.replica_index (key_kind, alg, hash_bytes, byte_length);

-- Durable, cluster-shared progress for idempotent replica convergence.
CREATE TABLE IF NOT EXISTS graviton.repair_state (
  namespace text PRIMARY KEY CHECK (length(namespace) BETWEEN 1 AND 128),
  next_offset bigint NOT NULL DEFAULT 0 CHECK (next_offset >= 0),
  updated_at timestamptz NOT NULL DEFAULT core.now_utc()
);

-- A stable repair namespace is reusable across maintenance cycles. When its
-- immutable tenant-membership digest changes, PgTenantDomainSnapshot resets
-- the cursor and stale dead letters in the same transaction before scanning.
CREATE TABLE IF NOT EXISTS graviton.tenant_domain_repair_epoch (
  namespace text PRIMARY KEY CHECK (length(namespace) BETWEEN 1 AND 128),
  membership_sha256 bytea NOT NULL CHECK (octet_length(membership_sha256) = 32),
  updated_at timestamptz NOT NULL DEFAULT core.now_utc()
);

CREATE TABLE IF NOT EXISTS graviton.repair_dead_letter (
  namespace text NOT NULL CHECK (length(namespace) BETWEEN 1 AND 128),
  alg core.hash_alg NOT NULL,
  hash_bytes bytea NOT NULL,
  byte_length core.byte_size NOT NULL CHECK (byte_length > 0),
  attempts bigint NOT NULL DEFAULT 1 CHECK (attempts > 0),
  last_error varchar(2048) NOT NULL,
  last_failed_at timestamptz NOT NULL,
  PRIMARY KEY (namespace, alg, hash_bytes, byte_length)
);
CREATE INDEX IF NOT EXISTS repair_dead_letter_failed_idx
  ON graviton.repair_dead_letter (namespace, last_failed_at DESC);

-- Durable resumable-upload control plane. Payload bytes live in the selected
-- MutableObjectStore; these rows retain only bounded metadata and opaque
-- locators. Row locks serialize append and commit leases across nodes.
CREATE TABLE IF NOT EXISTS graviton.upload_session (
  tenant_id uuid NOT NULL,
  upload_session_id uuid NOT NULL,
  content_type text NOT NULL CHECK (length(content_type) BETWEEN 3 AND 255),
  expected_size core.byte_size NULL CHECK (expected_size BETWEEN 1 AND 1099511627776),
  byte_offset core.byte_size NOT NULL DEFAULT 0 CHECK (byte_offset <= 1099511627776),
  part_count integer NOT NULL DEFAULT 0 CHECK (part_count BETWEEN 0 AND 65535),
  phase text NOT NULL DEFAULT 'Open' CHECK (phase IN ('Open', 'Committing', 'Committed', 'Cancelled')),
  committed_blob text NULL CHECK (committed_blob IS NULL OR length(committed_blob) BETWEEN 8 AND 256),
  commit_lease_id uuid NULL,
  commit_lease_expires_at timestamptz NULL,
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT core.now_utc(),
  PRIMARY KEY (tenant_id, upload_session_id),
  CONSTRAINT upload_session_commit_lease_pair CHECK (
    (commit_lease_id IS NULL AND commit_lease_expires_at IS NULL) OR
    (commit_lease_id IS NOT NULL AND commit_lease_expires_at IS NOT NULL)
  ),
  CONSTRAINT upload_session_committed_blob_state CHECK (
    (phase = 'Committed' AND committed_blob IS NOT NULL) OR
    (phase <> 'Committed' AND committed_blob IS NULL)
  )
);
CREATE INDEX IF NOT EXISTS upload_session_expiry_idx
  ON graviton.upload_session (expires_at, tenant_id, upload_session_id)
  WHERE phase <> 'Committed';

CREATE TABLE IF NOT EXISTS graviton.upload_part (
  tenant_id uuid NOT NULL,
  upload_session_id uuid NOT NULL,
  part_id uuid NOT NULL,
  part_number integer NOT NULL CHECK (part_number BETWEEN 0 AND 65535),
  byte_offset core.byte_size NOT NULL CHECK (byte_offset <= 1099511627776),
  byte_length core.byte_size NULL CHECK (byte_length BETWEEN 1 AND 1099511627776),
  locator text NOT NULL CHECK (locator ~ '^[a-z][a-z0-9+.-]*://'),
  lease_id uuid NULL,
  lease_expires_at timestamptz NULL,
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  completed_at timestamptz NULL,
  PRIMARY KEY (tenant_id, upload_session_id, part_id),
  UNIQUE (tenant_id, upload_session_id, part_number),
  FOREIGN KEY (tenant_id, upload_session_id)
    REFERENCES graviton.upload_session(tenant_id, upload_session_id)
    ON DELETE CASCADE,
  CONSTRAINT upload_part_state CHECK (
    (byte_length IS NULL AND lease_id IS NOT NULL AND lease_expires_at IS NOT NULL AND completed_at IS NULL) OR
    (byte_length IS NOT NULL AND lease_id IS NULL AND lease_expires_at IS NULL AND completed_at IS NOT NULL)
  )
);
CREATE UNIQUE INDEX IF NOT EXISTS upload_part_one_reservation_idx
  ON graviton.upload_part (tenant_id, upload_session_id)
  WHERE byte_length IS NULL;

-- A policy update must invalidate every server's cached route. Operators do
-- not need to coordinate or remember a revision value by hand.
CREATE OR REPLACE FUNCTION graviton.bump_tenant_policy_revision()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
    RAISE EXCEPTION 'tenant_id is immutable';
  END IF;
  NEW.revision := OLD.revision + 1;
  NEW.updated_at := clock_timestamp();
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS tenant_storage_policy_revision_trg ON graviton.tenant_storage_policy;
CREATE TRIGGER tenant_storage_policy_revision_trg
BEFORE UPDATE ON graviton.tenant_storage_policy
FOR EACH ROW EXECUTE FUNCTION graviton.bump_tenant_policy_revision();

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

-- Bounded control-plane values. The Scala API retains the same 32 MiB
-- maximum in KvValue, and PostgreSQL rejects oversized values independently.
CREATE TABLE IF NOT EXISTS graviton.key_value (
  key        text PRIMARY KEY CHECK (length(key) BETWEEN 1 AND 1024),
  value      bytea NOT NULL CHECK (octet_length(value) <= 33554432),
  updated_at timestamptz NOT NULL DEFAULT core.now_utc()
);

-- Direct object storage is intentionally chunked. PostgreSQL never receives a
-- whole arbitrary-size object as one bytea value, and an interrupted upload is
-- rolled back as a single transaction.
CREATE TABLE IF NOT EXISTS graviton.object_data (
  locator     text PRIMARY KEY CHECK (locator ~ '^[a-z][a-z0-9+.-]*://'),
  scheme      core.nonempty_text NOT NULL,
  bucket      core.nonempty_text NOT NULL,
  path        core.nonempty_text NOT NULL,
  byte_length core.byte_size NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT core.now_utc(),
  updated_at  timestamptz NOT NULL DEFAULT core.now_utc(),
  UNIQUE (scheme, bucket, path)
);
CREATE INDEX IF NOT EXISTS object_data_prefix_idx ON graviton.object_data (locator text_pattern_ops);

CREATE TABLE IF NOT EXISTS graviton.object_chunk (
  locator text NOT NULL REFERENCES graviton.object_data(locator) ON DELETE CASCADE,
  ordinal bigint NOT NULL CHECK (ordinal >= 0),
  bytes   bytea NOT NULL CHECK (octet_length(bytes) BETWEEN 1 AND 1048576),
  PRIMARY KEY (locator, ordinal)
);

-- Tenant and storage-domain scoped blocks.
-- Multi-tenant data plane. Storage-domain identity is part of every block key,
-- while tenant identity is part of every blob and manifest key. This permits
-- explicit block sharing without granting cross-tenant blob ownership.
CREATE TABLE graviton.tenant_block (
  storage_domain_id varchar(128) NOT NULL CHECK (storage_domain_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
  alg core.hash_alg NOT NULL,
  hash_bytes bytea NOT NULL,
  byte_length core.byte_size NOT NULL CHECK (byte_length > 0),
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  PRIMARY KEY (storage_domain_id, alg, hash_bytes, byte_length),
  CONSTRAINT tenant_block_key_valid CHECK (graviton.is_valid_cas_key(alg, hash_bytes, byte_length))
);
CREATE INDEX tenant_block_created_idx ON graviton.tenant_block (storage_domain_id, created_at DESC);

CREATE TABLE graviton.tenant_blob (
  tenant_id uuid NOT NULL,
  storage_domain_id varchar(128) NOT NULL,
  alg core.hash_alg NOT NULL,
  hash_bytes bytea NOT NULL,
  byte_length core.byte_size NOT NULL,
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  block_count int NOT NULL CHECK (block_count >= 0),
  metadata jsonb NOT NULL,
  manifest_proof_version smallint NULL,
  manifest_chunker varchar(120) NULL,
  manifest_key_id varchar(120) NULL,
  manifest_merkle_root bytea NULL,
  manifest_signature bytea NULL,
  PRIMARY KEY (tenant_id, storage_domain_id, alg, hash_bytes, byte_length),
  CONSTRAINT tenant_blob_key_valid CHECK (graviton.is_valid_cas_key(alg, hash_bytes, byte_length)),
  CONSTRAINT tenant_blob_metadata_valid CHECK (
    jsonb_typeof(metadata) = 'object'
    AND metadata @> '{"schemaId":"graviton.blob-metadata","schemaVersion":1,"codecVersion":1}'::jsonb
    AND octet_length(metadata::text) <= 1024
  ),
  CONSTRAINT tenant_blob_policy_fk FOREIGN KEY (tenant_id) REFERENCES graviton.tenant_storage_policy(tenant_id),
  CONSTRAINT tenant_blob_manifest_proof_complete CHECK (
    (manifest_proof_version IS NULL AND manifest_chunker IS NULL AND manifest_key_id IS NULL
      AND manifest_merkle_root IS NULL AND manifest_signature IS NULL)
    OR
    (manifest_proof_version = 3 AND manifest_chunker IS NOT NULL AND manifest_key_id IS NOT NULL
      AND octet_length(manifest_merkle_root) = 32 AND octet_length(manifest_signature) = 32)
  )
);
CREATE INDEX tenant_blob_inventory_idx
  ON graviton.tenant_blob (tenant_id, storage_domain_id, alg, hash_bytes, byte_length);

CREATE TABLE graviton.tenant_blob_block (
  tenant_id uuid NOT NULL,
  storage_domain_id varchar(128) NOT NULL,
  alg core.hash_alg NOT NULL,
  hash_bytes bytea NOT NULL,
  byte_length core.byte_size NOT NULL,
  ordinal int NOT NULL CHECK (ordinal >= 0),
  block_alg core.hash_alg NOT NULL,
  block_hash_bytes bytea NOT NULL,
  block_byte_length core.byte_size NOT NULL,
  block_offset core.byte_size NOT NULL,
  block_length core.byte_size NOT NULL CHECK (block_length > 0),
  span int8range GENERATED ALWAYS AS (int8range(block_offset, block_offset + block_length, '[)')) STORED,
  PRIMARY KEY (tenant_id, storage_domain_id, alg, hash_bytes, byte_length, ordinal),
  FOREIGN KEY (tenant_id, storage_domain_id, alg, hash_bytes, byte_length)
    REFERENCES graviton.tenant_blob(tenant_id, storage_domain_id, alg, hash_bytes, byte_length),
  FOREIGN KEY (storage_domain_id, block_alg, block_hash_bytes, block_byte_length)
    REFERENCES graviton.tenant_block(storage_domain_id, alg, hash_bytes, byte_length)
);
CREATE INDEX tenant_blob_block_read_idx
  ON graviton.tenant_blob_block (tenant_id, storage_domain_id, alg, hash_bytes, byte_length, ordinal);
ALTER TABLE graviton.tenant_blob_block
  ADD CONSTRAINT tenant_blob_block_non_overlapping
  EXCLUDE USING gist (
    tenant_id WITH =,
    storage_domain_id WITH =,
    alg WITH =,
    hash_bytes WITH =,
    byte_length WITH =,
    span WITH &&
  );

-- Defense in depth for the data plane. The application still binds every
-- tenant predicate explicitly, while FORCE ROW LEVEL SECURITY makes a missed
-- predicate fail closed for the non-superuser runtime role. Shared physical
-- blocks intentionally remain domain-scoped rather than tenant-scoped.
ALTER TABLE graviton.tenant_storage_usage ENABLE ROW LEVEL SECURITY;
ALTER TABLE graviton.tenant_storage_usage FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_storage_usage_org_isolation
  ON graviton.tenant_storage_usage
  USING (tenant_id = graviton.current_org_id())
  WITH CHECK (tenant_id = graviton.current_org_id());

ALTER TABLE graviton.tenant_blob ENABLE ROW LEVEL SECURITY;
ALTER TABLE graviton.tenant_blob FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_blob_org_isolation
  ON graviton.tenant_blob
  USING (tenant_id = graviton.current_org_id())
  WITH CHECK (tenant_id = graviton.current_org_id());

ALTER TABLE graviton.tenant_blob_block ENABLE ROW LEVEL SECURITY;
ALTER TABLE graviton.tenant_blob_block FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_blob_block_org_isolation
  ON graviton.tenant_blob_block
  USING (tenant_id = graviton.current_org_id())
  WITH CHECK (tenant_id = graviton.current_org_id());

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

-- Single-domain blobs and manifests.
CREATE TABLE graviton.blob (
  alg         core.hash_alg NOT NULL,
  hash_bytes  bytea NOT NULL,
  byte_length core.byte_size NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT core.now_utc(),
  block_count int NOT NULL,
  chunker     jsonb NOT NULL DEFAULT '{}'::jsonb,
  attrs       jsonb NOT NULL DEFAULT '{}'::jsonb,
  metadata    jsonb NOT NULL,
  manifest_proof_version smallint NULL,
  manifest_chunker varchar(120) NULL,
  manifest_key_id varchar(120) NULL,
  manifest_merkle_root bytea NULL,
  manifest_signature bytea NULL,
  PRIMARY KEY (alg, hash_bytes, byte_length),
  CONSTRAINT blob_key_valid CHECK (graviton.is_valid_cas_key(alg, hash_bytes, byte_length)),
  CONSTRAINT blob_block_count_nonneg CHECK (block_count >= 0),
  CONSTRAINT chunker_is_object CHECK (jsonb_typeof(chunker) = 'object'),
  CONSTRAINT attrs_is_object CHECK (jsonb_typeof(attrs) = 'object'),
  CONSTRAINT blob_metadata_valid CHECK (
    jsonb_typeof(metadata) = 'object'
    AND metadata @> '{"schemaId":"graviton.blob-metadata","schemaVersion":1,"codecVersion":1}'::jsonb
    AND octet_length(metadata::text) <= 1024
  ),
  CONSTRAINT blob_manifest_proof_complete CHECK (
    (manifest_proof_version IS NULL AND manifest_chunker IS NULL AND manifest_key_id IS NULL
      AND manifest_merkle_root IS NULL AND manifest_signature IS NULL)
    OR
    (manifest_proof_version = 3 AND manifest_chunker IS NOT NULL AND manifest_key_id IS NOT NULL
      AND octet_length(manifest_merkle_root) = 32 AND octet_length(manifest_signature) = 32)
  )
);
CREATE INDEX blob_created_idx ON graviton.blob (created_at DESC);

-- Mutable names and folders remain orthogonal to immutable CAS identity.
-- Deleting a catalog entry never cascades into graviton.blob or its blocks.
CREATE TABLE graviton.catalog_entry (
  entry_id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  parent_id        uuid NULL REFERENCES graviton.catalog_entry(entry_id) ON DELETE RESTRICT,
  kind             text NOT NULL CHECK (kind IN ('folder', 'file')),
  name             text NOT NULL CHECK (name !~ '[[:cntrl:]]'),
  name_key         text GENERATED ALWAYS AS (lower(name)) STORED,
  blob_alg         core.hash_alg NULL,
  blob_hash_bytes  bytea NULL,
  blob_byte_length core.byte_size NULL,
  media_type       text NULL,
  block_count      integer NULL,
  fresh_blocks     integer NULL,
  duplicate_blocks integer NULL,
  created_at       timestamptz NOT NULL DEFAULT core.now_utc(),
  CONSTRAINT catalog_name_valid CHECK (
    length(name) BETWEEN 1 AND 255
    AND name = btrim(name)
    AND position('/' in name) = 0
    AND position(chr(92) in name) = 0
  ),
  CONSTRAINT catalog_payload_matches_kind CHECK (
    (kind = 'folder'
      AND blob_alg IS NULL AND blob_hash_bytes IS NULL AND blob_byte_length IS NULL
      AND media_type IS NULL AND block_count IS NULL AND fresh_blocks IS NULL AND duplicate_blocks IS NULL)
    OR
    (kind = 'file'
      AND blob_alg IS NOT NULL AND blob_hash_bytes IS NOT NULL AND blob_byte_length IS NOT NULL
      AND media_type IS NOT NULL AND block_count >= 0 AND fresh_blocks >= 0 AND duplicate_blocks >= 0)
  ),
  CONSTRAINT catalog_blob_fk FOREIGN KEY (blob_alg, blob_hash_bytes, blob_byte_length)
    REFERENCES graviton.blob(alg, hash_bytes, byte_length),
  CONSTRAINT catalog_no_self_parent CHECK (parent_id IS NULL OR parent_id <> entry_id)
);
CREATE INDEX catalog_entry_parent_idx
  ON graviton.catalog_entry (parent_id, kind, name_key, entry_id);
CREATE UNIQUE INDEX catalog_entry_nested_name_uq
  ON graviton.catalog_entry (parent_id, name_key)
  WHERE parent_id IS NOT NULL;
CREATE UNIQUE INDEX catalog_entry_root_name_uq
  ON graviton.catalog_entry (name_key)
  WHERE parent_id IS NULL;

CREATE FUNCTION graviton.catalog_parent_must_be_folder()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.parent_id IS NOT NULL AND NOT EXISTS (
    SELECT 1
    FROM graviton.catalog_entry parent
    WHERE parent.entry_id = NEW.parent_id AND parent.kind = 'folder'
  ) THEN
    RAISE EXCEPTION 'catalog parent % is not a folder', NEW.parent_id
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER catalog_entry_parent_kind_trg
BEFORE INSERT OR UPDATE OF parent_id ON graviton.catalog_entry
FOR EACH ROW EXECUTE FUNCTION graviton.catalog_parent_must_be_folder();

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

-- ---------------- Security and audit -----------------------------

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'graviton' AND t.typname = 'resource_kind'
  ) THEN
    EXECUTE 'CREATE TYPE graviton.resource_kind AS ENUM (''blob'',''catalog'',''observability'',''audit'')';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'graviton' AND t.typname = 'acl_effect'
  ) THEN
    EXECUTE 'CREATE TYPE graviton.acl_effect AS ENUM (''allow'',''deny'')';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'graviton' AND t.typname = 'audit_outcome'
  ) THEN
    EXECUTE 'CREATE TYPE graviton.audit_outcome AS ENUM (''allow'',''deny'',''error'',''auth_fail'')';
  END IF;
END $$;

CREATE TABLE graviton.acl_entry (
  org_id uuid NOT NULL,
  principal_id uuid NOT NULL,
  resource_kind graviton.resource_kind NOT NULL,
  resource_id uuid NOT NULL,
  capabilities bigint NOT NULL CHECK (capabilities >= 0),
  effect graviton.acl_effect NOT NULL,
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  PRIMARY KEY (org_id, principal_id, resource_kind, resource_id, effect)
);
CREATE INDEX graviton_acl_resource_idx
  ON graviton.acl_entry (org_id, resource_kind, resource_id);

CREATE TABLE graviton.audit_log (
  org_id uuid NOT NULL,
  seq bigint NOT NULL CHECK (seq > 0),
  ts timestamptz NOT NULL DEFAULT core.now_utc(),
  principal_id uuid NOT NULL,
  action core.nonempty_text NOT NULL CHECK (length(action) <= 255),
  resource_kind graviton.resource_kind NOT NULL,
  resource_id uuid NULL,
  request_id uuid NOT NULL,
  source_ip inet NULL,
  user_agent text NULL CHECK (length(user_agent) <= 1024),
  outcome graviton.audit_outcome NOT NULL,
  reason text NULL CHECK (length(reason) <= 2048),
  bytes core.byte_size NULL,
  prev_hash bytea NOT NULL CHECK (octet_length(prev_hash) = 32),
  row_hash bytea NOT NULL CHECK (octet_length(row_hash) = 32),
  PRIMARY KEY (org_id, seq)
);
CREATE INDEX graviton_audit_ts_idx
  ON graviton.audit_log (org_id, ts DESC);
CREATE INDEX graviton_audit_resource_idx
  ON graviton.audit_log (org_id, resource_kind, resource_id, ts DESC);

ALTER TABLE graviton.acl_entry ENABLE ROW LEVEL SECURITY;
CREATE POLICY graviton_acl_org_isolation
  ON graviton.acl_entry
  USING (org_id = graviton.current_org_id())
  WITH CHECK (org_id = graviton.current_org_id());

ALTER TABLE graviton.audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY graviton_audit_org_isolation
  ON graviton.audit_log
  USING (org_id = graviton.current_org_id())
  WITH CHECK (org_id = graviton.current_org_id());
