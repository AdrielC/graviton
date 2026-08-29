-- Graviton authoritative schema (pre-1.0: major overhauls welcome)
--
-- Target: Postgres 18+.
-- Notes:
-- - We target PG18 for I/O improvements + UUIDv7 support, but the DDL still avoids
--   non-portable features where not required (prefer explicitness over magic).
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

-- Library-level replica catalog. This complements physical block_location
-- topology and also supports logical blob replicas exposed by ReplicaIndex.
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

-- Generic change notification trigger for Graviton cache invalidation and subscriptions.
-- Emits JSON payloads to the 'graviton_inval' channel.
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

CREATE OR REPLACE FUNCTION graviton.current_org_id()
RETURNS uuid
LANGUAGE sql
STABLE
AS $$
  SELECT nullif(current_setting('app.org_id', true), '')::uuid;
$$;

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
  action core.nonempty_text NOT NULL,
  resource_kind graviton.resource_kind NOT NULL,
  resource_id uuid NULL,
  request_id uuid NOT NULL,
  source_ip inet NULL,
  user_agent text NULL,
  outcome graviton.audit_outcome NOT NULL,
  reason text NULL,
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
