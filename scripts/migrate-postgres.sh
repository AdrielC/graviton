#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIGRATIONS_DIR="${GRAVITON_MIGRATIONS_DIR:-${REPO_ROOT}/modules/backend/graviton-pg/src/main/resources/db/migration}"
DATABASE_URL="${GRAVITON_DATABASE_URL:-}"

if [[ -z "${DATABASE_URL}" ]]; then
  echo "GRAVITON_DATABASE_URL is required (prefer a password-free URI plus PGPASSWORD)" >&2
  exit 2
fi
command -v psql >/dev/null || { echo "psql is required" >&2; exit 2; }
[[ -d "${MIGRATIONS_DIR}" ]] || { echo "Migration directory not found: ${MIGRATIONS_DIR}" >&2; exit 2; }

shopt -s nullglob
MIGRATIONS=("${MIGRATIONS_DIR}"/V[0-9][0-9][0-9]__*.sql)
shopt -u nullglob

if [[ ${#MIGRATIONS[@]} -eq 0 ]]; then
  echo "No Graviton migrations found in ${MIGRATIONS_DIR}" >&2
  exit 2
fi

checksum() {
  if command -v sha256sum >/dev/null; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

previous=""
for migration in "${MIGRATIONS[@]}"; do
  filename="$(basename "${migration}")"
  version="${filename%%__*}"
  version="${version#V}"

  if [[ "${version}" == "${previous}" ]]; then
    echo "Duplicate Graviton migration version ${version}" >&2
    exit 2
  fi
  previous="${version}"

  migration_checksum="$(checksum "${migration}")"
  psql --dbname="${DATABASE_URL}" -X -v ON_ERROR_STOP=1 \
    --set=migration_file="${migration}" \
    --set=migration_version="${version}" \
    --set=migration_checksum="${migration_checksum}" <<'SQL'
BEGIN;
SELECT pg_advisory_xact_lock(hashtextextended('graviton-schema-migrations', 0));
CREATE TABLE IF NOT EXISTS public.graviton_schema_migrations (
  version text PRIMARY KEY,
  checksum text NOT NULL,
  applied_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
SELECT
  count(*) AS already_applied,
  count(*) FILTER (WHERE checksum <> :'migration_checksum') AS checksum_drift
FROM public.graviton_schema_migrations
WHERE version = :'migration_version'
\gset
\if :checksum_drift
  \echo 'Graviton migration checksum drift for version' :migration_version
  ROLLBACK;
  \quit 1
\endif
\if :already_applied
  \echo 'Graviton migration already applied:' :migration_version
\else
  \i :migration_file
  INSERT INTO public.graviton_schema_migrations(version, checksum)
  VALUES (:'migration_version', :'migration_checksum');
  \echo 'Applied Graviton migration:' :migration_version
\endif
COMMIT;
SQL
done
