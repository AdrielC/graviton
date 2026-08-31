#!/usr/bin/env bash
set -euo pipefail

: "${PG_HOST:?PG_HOST is required}"
: "${PG_PORT:?PG_PORT is required}"
: "${PG_DATABASE:?PG_DATABASE is required}"
: "${PG_ADMIN_USERNAME:?PG_ADMIN_USERNAME is required}"
: "${PG_ADMIN_PASSWORD:?PG_ADMIN_PASSWORD is required}"
: "${GRAVITON_POSTGRES_PASSWORD:?GRAVITON_POSTGRES_PASSWORD is required}"
: "${GRAVITON_INITIAL_TENANT_ID:?GRAVITON_INITIAL_TENANT_ID is required}"
: "${GRAVITON_CELL_ID:?GRAVITON_CELL_ID is required}"
: "${GRAVITON_INITIAL_TENANT_MAX_RETAINED_BYTES:?GRAVITON_INITIAL_TENANT_MAX_RETAINED_BYTES is required}"

export PGPASSWORD="${PG_ADMIN_PASSWORD}"
export PGSSLMODE=require
export GRAVITON_MIGRATIONS_DIR=/opt/graviton/migrations
export GRAVITON_DATABASE_URL="postgresql://${PG_ADMIN_USERNAME}@${PG_HOST}:${PG_PORT}/${PG_DATABASE}"

/opt/graviton/migrate-postgres.sh

export POSTGRES_USER="${PG_ADMIN_USERNAME}"
export POSTGRES_DB="${PG_DATABASE}"
export PGHOST="${PG_HOST}"
export PGPORT="${PG_PORT}"
/opt/graviton/init-postgres-app-role.sh

export PG_JDBC_URL="jdbc:postgresql://${PG_HOST}:${PG_PORT}/${PG_DATABASE}?sslmode=require"
export PG_ADMIN_USERNAME
export PG_ADMIN_PASSWORD
/opt/graviton/provision-tenant.sh \
  "${GRAVITON_INITIAL_TENANT_ID}" \
  isolated \
  "${GRAVITON_CELL_ID}" \
  1099511627776 \
  "${GRAVITON_INITIAL_TENANT_MAX_RETAINED_BYTES}" \
  32

privileges="$(psql -X -A -t -v ON_ERROR_STOP=1 -c \
  "SELECT concat_ws(':',
     has_table_privilege('graviton_app', 'graviton.tenant_storage_policy', 'SELECT')::text,
     has_table_privilege('graviton_app', 'graviton.tenant_storage_policy', 'UPDATE')::text,
     has_table_privilege('graviton_app', 'graviton.acl_entry', 'SELECT')::text,
     has_table_privilege('graviton_app', 'graviton.acl_entry', 'INSERT')::text,
     has_table_privilege('graviton_app', 'graviton.audit_log', 'INSERT')::text,
     has_table_privilege('graviton_app', 'graviton.audit_log', 'DELETE')::text)")"

if [[ "${privileges}" != "true:false:true:false:true:false" ]]; then
  echo "runtime role privilege verification failed" >&2
  exit 1
fi

echo "Graviton empty-store schema and runtime role are ready"
