#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 6 ]]; then
  echo "usage: $0 <tenant-uuid> [isolated|shared:<domain>] [cell=default] [max-object-bytes=5368709120] [max-retained-bytes=1099511627776] [max-concurrent=32]" >&2
  exit 2
fi

tenant_id="$1"
scope="${2:-isolated}"
cell_id="${3:-default}"
max_object_bytes="${4:-5368709120}"
max_retained_bytes="${5:-1099511627776}"
max_concurrent="${6:-32}"

[[ "$tenant_id" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || {
  echo "tenant-uuid must be a canonical lowercase UUID" >&2
  exit 2
}
[[ "$cell_id" =~ ^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$ ]] || { echo "cell is invalid" >&2; exit 2; }
[[ "$max_object_bytes" =~ ^[1-9][0-9]*$ ]] || { echo "max-object-bytes must be positive" >&2; exit 2; }
[[ "$max_retained_bytes" =~ ^[1-9][0-9]*$ ]] || { echo "max-retained-bytes must be positive" >&2; exit 2; }
[[ "$max_concurrent" =~ ^[1-9][0-9]*$ ]] || { echo "max-concurrent must be positive" >&2; exit 2; }

# Compare canonical positive decimal strings without shell arithmetic. Bash
# arithmetic is signed-machine-width and can wrap attacker-controlled values
# before PostgreSQL sees them.
decimal_lte() {
  local left="$1"
  local right="$2"
  if ((${#left} < ${#right})); then return 0; fi
  if ((${#left} > ${#right})); then return 1; fi
  [[ "$left" == "$right" || "$left" < "$right" ]]
}

decimal_lte "$max_object_bytes" "1099511627776" || { echo "max-object-bytes cannot exceed 1 TiB" >&2; exit 2; }
decimal_lte "$max_retained_bytes" "9223372036854775807" || { echo "max-retained-bytes cannot exceed signed 64-bit storage" >&2; exit 2; }
decimal_lte "$max_object_bytes" "$max_retained_bytes" || { echo "max-retained-bytes must be at least max-object-bytes" >&2; exit 2; }
decimal_lte "$max_concurrent" "65535" || { echo "max-concurrent cannot exceed 65535" >&2; exit 2; }

case "$scope" in
  isolated)
    deduplication_domain=""
    ;;
  shared:*)
    deduplication_domain="${scope#shared:}"
    [[ "$deduplication_domain" =~ ^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$ ]] || {
      echo "shared deduplication domain is invalid" >&2
      exit 2
    }
    ;;
  *)
    echo "scope must be isolated or shared:<domain>" >&2
    exit 2
    ;;
esac

: "${PG_JDBC_URL:?Set PG_JDBC_URL}"
: "${PG_ADMIN_USERNAME:?Set PG_ADMIN_USERNAME to a tenant-policy control-plane role}"
: "${PG_ADMIN_PASSWORD:?Set PG_ADMIN_PASSWORD to the control-plane credential}"
command -v psql >/dev/null || { echo "psql is required" >&2; exit 2; }

postgres_url="${PG_JDBC_URL#jdbc:}"
PGPASSWORD="$PG_ADMIN_PASSWORD" psql "$postgres_url" -U "$PG_ADMIN_USERNAME" -v ON_ERROR_STOP=1 \
  -v tenant_id="$tenant_id" \
  -v cell_id="$cell_id" \
  -v deduplication_domain="$deduplication_domain" \
  -v max_object_bytes="$max_object_bytes" \
  -v max_retained_bytes="$max_retained_bytes" \
  -v max_concurrent="$max_concurrent" <<'SQL'
INSERT INTO graviton.tenant_storage_policy (
  tenant_id,
  cell_id,
  lifecycle,
  deduplication_domain,
  max_concurrent_operations,
  max_object_bytes,
  max_retained_bytes
) VALUES (
  :'tenant_id'::uuid,
  :'cell_id',
  'active',
  NULLIF(:'deduplication_domain', ''),
  :'max_concurrent'::integer,
  :'max_object_bytes'::bigint,
  :'max_retained_bytes'::bigint
)
ON CONFLICT (tenant_id) DO UPDATE SET
  cell_id = EXCLUDED.cell_id,
  lifecycle = EXCLUDED.lifecycle,
  deduplication_domain = EXCLUDED.deduplication_domain,
  max_concurrent_operations = EXCLUDED.max_concurrent_operations,
  max_object_bytes = EXCLUDED.max_object_bytes,
  max_retained_bytes = EXCLUDED.max_retained_bytes
RETURNING tenant_id, cell_id, lifecycle, deduplication_domain,
          max_concurrent_operations, max_object_bytes, max_retained_bytes, revision;
SQL
