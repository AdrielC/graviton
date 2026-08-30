#!/usr/bin/env bash
set -euo pipefail

: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${GRAVITON_POSTGRES_PASSWORD:?GRAVITON_POSTGRES_PASSWORD is required}"

# This script runs after the canonical DDL during first-volume initialization.
# The runtime credential is intentionally distinct from the bootstrap owner.
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 \
  --set=db_name="$POSTGRES_DB" \
  --set=app_password="$GRAVITON_POSTGRES_PASSWORD" <<'SQL'
SELECT format(
  'CREATE ROLE graviton_app LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS',
  :'app_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'graviton_app')
\gexec

SELECT format('ALTER ROLE graviton_app PASSWORD %L', :'app_password')
\gexec
ALTER ROLE graviton_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

GRANT CONNECT ON DATABASE :"db_name" TO graviton_app;
GRANT USAGE ON SCHEMA core, graviton TO graviton_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA graviton TO graviton_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA graviton TO graviton_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA core, graviton TO graviton_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA graviton
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO graviton_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA core
  GRANT EXECUTE ON FUNCTIONS TO graviton_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA graviton
  GRANT EXECUTE ON FUNCTIONS TO graviton_app;

-- Tenant placement, lifecycle, limits, and sharing are control-plane data.
-- The data-plane runtime may resolve policy but cannot rewrite it.
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER
  ON graviton.tenant_storage_policy FROM graviton_app;
GRANT SELECT ON graviton.tenant_storage_policy TO graviton_app;

-- Authorization policy is also control-plane owned. The runtime resolves ACLs
-- but cannot grant itself capabilities or remove a deny.
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER
  ON graviton.acl_entry FROM graviton_app;
GRANT SELECT ON graviton.acl_entry TO graviton_app;

-- Audit rows are append-only from the data plane. SELECT is needed to extend
-- and verify the per-org hash chain; INSERT records the next event.
REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER
  ON graviton.audit_log FROM graviton_app;
GRANT SELECT, INSERT ON graviton.audit_log TO graviton_app;
SQL
