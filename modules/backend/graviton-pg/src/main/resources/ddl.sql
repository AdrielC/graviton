-- Deployable Postgres DDL for Graviton + Quasar.
--
-- This file intentionally mirrors `modules/pg/ddl.sql`.
-- Keep them in lockstep (alpha: we optimize for correctness and operability).

SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SET check_function_bodies = off;

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gin;
CREATE EXTENSION IF NOT EXISTS btree_gist;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'vector') THEN
    EXECUTE 'CREATE EXTENSION IF NOT EXISTS vector';
  END IF;
END $$;

CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS graviton;
CREATE SCHEMA IF NOT EXISTS quasar;

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

CREATE OR REPLACE FUNCTION core.touch_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  NEW.updated_at := clock_timestamp();
  RETURN NEW;
END;
$$;

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

CREATE TABLE graviton.blob_store (
  blob_store_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  type_id       core.nonempty_text NOT NULL,
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
  priority      int NOT NULL DEFAULT 100,
  policy        jsonb NOT NULL DEFAULT '{}'::jsonb,
  status        core.lifecycle_status NOT NULL DEFAULT 'active',
  created_at    timestamptz NOT NULL DEFAULT core.now_utc(),
  CONSTRAINT sector_policy_is_object CHECK (jsonb_typeof(policy) = 'object'),
  UNIQUE (blob_store_id, name)
);
CREATE INDEX sector_read_pref_idx ON graviton.sector (status, priority, sector_id);

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
  principal_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id       uuid NOT NULL REFERENCES quasar.org(org_id),
  kind         quasar.principal_kind NOT NULL,
  display_name text NOT NULL,
  status       core.lifecycle_status NOT NULL DEFAULT 'active',
  created_at   timestamptz NOT NULL DEFAULT core.now_utc()
);
CREATE INDEX principal_org_kind_idx ON quasar.principal (org_id, kind, status);

CREATE TABLE quasar.principal_external_identity (
  principal_id uuid NOT NULL REFERENCES quasar.principal(principal_id) ON DELETE CASCADE,
  issuer       core.nonempty_text NOT NULL,
  subject      core.nonempty_text NOT NULL,
  claims       jsonb NOT NULL DEFAULT '{}'::jsonb,
  last_seen_at timestamptz NOT NULL DEFAULT core.now_utc(),
  PRIMARY KEY (principal_id, issuer, subject),
  UNIQUE (issuer, subject),
  CONSTRAINT claims_is_object CHECK (jsonb_typeof(claims) = 'object')
);

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
  upload_session_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES quasar.org(org_id),
  created_by_principal_id uuid NOT NULL REFERENCES quasar.principal(principal_id),
  status quasar.upload_status NOT NULL DEFAULT 'open',
  expires_at timestamptz NOT NULL,
  constraints jsonb NOT NULL DEFAULT '{}'::jsonb,
  client_context jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  CONSTRAINT constraints_is_object CHECK (jsonb_typeof(constraints) = 'object'),
  CONSTRAINT client_context_is_object CHECK (jsonb_typeof(client_context) = 'object')
);

CREATE TABLE quasar.upload_file (
  upload_file_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  upload_session_id uuid NOT NULL REFERENCES quasar.upload_session(upload_session_id) ON DELETE CASCADE,
  client_file_name text NOT NULL,
  declared_size core.byte_size NULL,
  declared_hash jsonb NULL,
  status quasar.upload_file_status NOT NULL DEFAULT 'uploading',
  result_blob_ref jsonb NULL,
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  CONSTRAINT declared_hash_is_object CHECK (declared_hash IS NULL OR jsonb_typeof(declared_hash) = 'object'),
  CONSTRAINT result_blob_ref_is_object CHECK (result_blob_ref IS NULL OR jsonb_typeof(result_blob_ref) = 'object')
);

CREATE TABLE quasar.upload_part (
  upload_file_id uuid NOT NULL REFERENCES quasar.upload_file(upload_file_id) ON DELETE CASCADE,
  part_no int NOT NULL,
  byte_range int8range NOT NULL,
  etag text NULL,
  status core.present_status NOT NULL DEFAULT 'present',
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  PRIMARY KEY (upload_file_id, part_no),
  CONSTRAINT nonempty_range CHECK (lower(byte_range) < upper(byte_range))
);
CREATE INDEX upload_part_range_gist ON quasar.upload_part USING gist (upload_file_id, byte_range);

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
  doc_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES quasar.org(org_id),
  created_by_principal_id uuid NOT NULL REFERENCES quasar.principal(principal_id),
  title text NOT NULL,
  status quasar.doc_status NOT NULL DEFAULT 'draft',
  created_at timestamptz NOT NULL DEFAULT core.now_utc()
);

CREATE TABLE quasar.document_version (
  doc_version_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  doc_id uuid NOT NULL REFERENCES quasar.document(doc_id) ON DELETE CASCADE,
  version int NOT NULL,
  created_by_principal_id uuid NOT NULL REFERENCES quasar.principal(principal_id),
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  content_kind quasar.content_kind NOT NULL,
  content_ref jsonb NOT NULL,
  UNIQUE (doc_id, version),
  CONSTRAINT content_ref_is_object CHECK (jsonb_typeof(content_ref) = 'object')
);
CREATE INDEX document_version_doc_idx ON quasar.document_version (doc_id, version DESC);

CREATE TABLE quasar.document_current (
  doc_id uuid PRIMARY KEY REFERENCES quasar.document(doc_id) ON DELETE CASCADE,
  org_id uuid NOT NULL REFERENCES quasar.org(org_id),
  current_version_id uuid NOT NULL REFERENCES quasar.document_version(doc_version_id),
  content_kind quasar.content_kind NOT NULL,
  content_ref jsonb NOT NULL,
  status quasar.doc_status NOT NULL,
  title text NOT NULL,
  last_modified_at timestamptz NOT NULL DEFAULT core.now_utc(),
  CONSTRAINT content_ref_is_object CHECK (jsonb_typeof(content_ref) = 'object')
);
CREATE INDEX document_current_org_status_idx ON quasar.document_current (org_id, status, last_modified_at DESC);

CREATE TABLE quasar.document_alias (
  org_id uuid NOT NULL REFERENCES quasar.org(org_id),
  system core.nonempty_text NOT NULL,
  external_id text NOT NULL,
  doc_id uuid NOT NULL REFERENCES quasar.document(doc_id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  PRIMARY KEY (org_id, system, external_id)
);
CREATE INDEX document_alias_doc_idx ON quasar.document_alias (doc_id);

CREATE TABLE quasar.namespace (
  namespace_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES quasar.org(org_id),
  urn core.nonempty_text NOT NULL UNIQUE,
  status core.lifecycle_status NOT NULL DEFAULT 'active',
  created_at timestamptz NOT NULL DEFAULT core.now_utc()
);

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

CREATE TABLE quasar.schema_registry (
  schema_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NULL REFERENCES quasar.org(org_id),
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
  doc_version_id uuid NOT NULL REFERENCES quasar.document_version(doc_version_id) ON DELETE CASCADE,
  namespace_id uuid NOT NULL REFERENCES quasar.namespace(namespace_id),
  schema_id uuid NULL REFERENCES quasar.schema_registry(schema_id),
  data jsonb NOT NULL,
  is_valid boolean NOT NULL DEFAULT true,
  validation_errors jsonb NOT NULL DEFAULT '[]'::jsonb,
  PRIMARY KEY (doc_version_id, namespace_id),
  CONSTRAINT data_is_object_or_array CHECK (jsonb_typeof(data) IN ('object','array')),
  CONSTRAINT validation_errors_is_array CHECK (jsonb_typeof(validation_errors) = 'array')
);
CREATE INDEX doc_namespace_data_gin ON quasar.document_namespace USING gin (data jsonb_path_ops);

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
  acl_entry_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES quasar.org(org_id),
  resource_kind quasar.resource_kind NOT NULL,
  resource_id uuid NOT NULL,
  principal_id uuid NOT NULL REFERENCES quasar.principal(principal_id),
  effect quasar.effect NOT NULL,
  capabilities bigint NOT NULL,
  created_at timestamptz NOT NULL DEFAULT core.now_utc()
);
CREATE INDEX acl_resource_idx ON quasar.acl_entry (org_id, resource_kind, resource_id);
CREATE INDEX acl_principal_idx ON quasar.acl_entry (org_id, principal_id);

CREATE TABLE quasar.policy (
  policy_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES quasar.org(org_id),
  name core.nonempty_text NOT NULL,
  ast jsonb NOT NULL,
  status core.lifecycle_status NOT NULL DEFAULT 'active',
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  CONSTRAINT ast_is_object CHECK (jsonb_typeof(ast) = 'object'),
  UNIQUE (org_id, name)
);
CREATE INDEX policy_org_status_idx ON quasar.policy (org_id, status);

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
        doc_id uuid PRIMARY KEY REFERENCES quasar.document(doc_id) ON DELETE CASCADE,
        org_id uuid NOT NULL REFERENCES quasar.org(org_id),
        current_version_id uuid NOT NULL,
        model core.nonempty_text NOT NULL,
        dims int NOT NULL,
        embedding vector NOT NULL,
        status quasar.embedding_status NOT NULL DEFAULT 'pending',
        updated_at timestamptz NOT NULL DEFAULT core.now_utc(),
        CONSTRAINT dims_positive CHECK (dims > 0)
      );
    $sql$;
  END IF;
END $$;

CREATE TABLE quasar.outbox_job (
  job_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES quasar.org(org_id),
  kind core.nonempty_text NOT NULL,
  dedupe_key core.nonempty_text NOT NULL UNIQUE,
  payload jsonb NOT NULL,
  status core.job_status NOT NULL DEFAULT 'queued',
  lease_owner text NULL,
  lease_expires_at timestamptz NULL,
  attempts int NOT NULL DEFAULT 0,
  next_run_at timestamptz NOT NULL DEFAULT core.now_utc(),
  created_at timestamptz NOT NULL DEFAULT core.now_utc(),
  CONSTRAINT payload_is_object CHECK (jsonb_typeof(payload) = 'object')
);
CREATE INDEX outbox_runnable_idx ON quasar.outbox_job (status, next_run_at)
  WHERE status IN ('queued','leased');
CREATE INDEX outbox_lease_idx ON quasar.outbox_job (lease_owner, lease_expires_at);

CREATE OR REPLACE FUNCTION quasar.current_org_id()
RETURNS uuid
LANGUAGE sql
STABLE
AS $$
  SELECT nullif(current_setting('app.org_id', true), '')::uuid;
$$;
