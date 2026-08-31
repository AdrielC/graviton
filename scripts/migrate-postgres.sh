#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DDL_FILE="${GRAVITON_DDL_FILE:-${REPO_ROOT}/modules/backend/graviton-pg/src/main/resources/ddl.sql}"
DATABASE_URL="${GRAVITON_DATABASE_URL:-}"

if [[ -z "${DATABASE_URL}" ]]; then
  echo "GRAVITON_DATABASE_URL is required (prefer a password-free URI plus PGPASSWORD)" >&2
  exit 2
fi
command -v psql >/dev/null || { echo "psql is required" >&2; exit 2; }
[[ -f "${DDL_FILE}" ]] || { echo "DDL file not found: ${DDL_FILE}" >&2; exit 2; }

if command -v sha256sum >/dev/null; then
  CHECKSUM="$(sha256sum "${DDL_FILE}" | awk '{print $1}')"
else
  CHECKSUM="$(shasum -a 256 "${DDL_FILE}" | awk '{print $1}')"
fi

EXISTING="$(PGDATABASE="${DATABASE_URL}" psql -X -A -t -v ON_ERROR_STOP=1 -c \
  "SELECT checksum FROM public.graviton_schema_migrations WHERE version = '001'" 2>/dev/null || true)"

if [[ -n "${EXISTING}" && "${EXISTING}" != "${CHECKSUM}" ]]; then
  echo "Migration 001 checksum drift: database=${EXISTING} repository=${CHECKSUM}" >&2
  exit 1
fi

PGDATABASE="${DATABASE_URL}" psql -X -v ON_ERROR_STOP=1 \
  --set=ddl_file="${DDL_FILE}" --set=ddl_checksum="${CHECKSUM}" <<'SQL'
SELECT pg_advisory_lock(hashtextextended('graviton-schema-migrations', 0));
CREATE TABLE IF NOT EXISTS public.graviton_schema_migrations (
  version text PRIMARY KEY,
  checksum text NOT NULL,
  applied_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
SELECT count(*) AS already_applied
FROM public.graviton_schema_migrations
WHERE version = '001'
\gset
\if :already_applied
  \echo 'Graviton migration 001 already applied'
\else
  BEGIN;
  \i :ddl_file
  INSERT INTO public.graviton_schema_migrations(version, checksum) VALUES ('001', :'ddl_checksum');
  COMMIT;
  \echo 'Applied Graviton migration 001'
\endif
SELECT pg_advisory_unlock(hashtextextended('graviton-schema-migrations', 0));
SQL
