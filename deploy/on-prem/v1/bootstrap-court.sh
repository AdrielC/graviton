#!/usr/bin/env bash
# ============================================================================
# Graviton court-pilot bootstrap.
#
# Run AFTER `docker compose up -d` succeeds. This script:
#   1. Verifies every container is healthy.
#   2. Creates the first `quasar.org` + `quasar.principal` rows for the
#      court-admin identity, mirroring the UUIDs you set in .env.
#   3. Mints an initial HS256 JWT using the dev shared secret, saves it to
#      ./secrets/admin-token.jwt (mode 0600), and echoes an example curl
#      upload/download so the operator can smoke-test before handing off.
#   4. Runs a round-trip (upload + download + diff) of a small canary file
#      so we know the stack is actually serving.
#
# Safe to re-run; DB seeds use `ON CONFLICT DO NOTHING`.
# ============================================================================
set -euo pipefail

cd "$(dirname "$0")"

if [[ ! -f .env ]]; then
  echo "ERROR: .env is missing. Copy .env.court.example to .env and edit it first." >&2
  exit 1
fi

# shellcheck disable=SC1091
source ./.env

required_vars=(
  GRAVITON_HTTP_PORT
  GRAVITON_SECURITY_DEV_SHARED_SECRET
  GRAVITON_SECURITY_OIDC_ISSUER
  GRAVITON_SECURITY_OIDC_AUDIENCE
  COURT_ADMIN_ORG_ID
  COURT_ADMIN_PRINCIPAL_ID
  COURT_ADMIN_DISPLAY_NAME
  PG_USERNAME
  PG_PASSWORD
  POSTGRES_SUPERUSER
  POSTGRES_SUPERPASS
)
for v in "${required_vars[@]}"; do
  if [[ -z "${!v:-}" ]]; then
    echo "ERROR: required env var $v is empty in .env" >&2
    exit 1
  fi
done

if [[ "$GRAVITON_SECURITY_DEV_SHARED_SECRET" == CHANGE-ME-* ]]; then
  echo "ERROR: GRAVITON_SECURITY_DEV_SHARED_SECRET is still the placeholder. Replace it." >&2
  exit 1
fi

echo "==> Waiting for containers to be healthy..."
for svc in postgres minio graviton-node-1; do
  for i in {1..60}; do
    status=$(docker inspect --format='{{.State.Health.Status}}' "quasar-v1-onprem-${svc}-1" 2>/dev/null || echo "missing")
    if [[ "$status" == "healthy" || "$status" == "none" ]]; then break; fi
    sleep 2
  done
  echo "    ${svc}: ${status:-?}"
done

echo "==> Seeding tenant org + admin principal..."
PGPASSWORD="$POSTGRES_SUPERPASS" docker compose exec -T -e PGPASSWORD postgres \
  psql -U "$POSTGRES_SUPERUSER" -d quasar -v ON_ERROR_STOP=1 <<SQL
INSERT INTO quasar.tenant (tenant_id, name, status)
VALUES ('${COURT_ADMIN_ORG_ID}', 'court-pilot', 'active')
ON CONFLICT DO NOTHING;

INSERT INTO quasar.org (org_id, tenant_id, name, status)
VALUES ('${COURT_ADMIN_ORG_ID}', '${COURT_ADMIN_ORG_ID}', 'court-pilot', 'active')
ON CONFLICT DO NOTHING;

INSERT INTO quasar.principal (org_id, principal_id, display_name, status)
VALUES ('${COURT_ADMIN_ORG_ID}', '${COURT_ADMIN_PRINCIPAL_ID}', '${COURT_ADMIN_DISPLAY_NAME}', 'active')
ON CONFLICT DO NOTHING;

-- Grant the admin a broad ACL covering blob + document operations.
INSERT INTO quasar.acl_entry
  (org_id, principal_id, resource_kind, resource_id, effect, capabilities)
SELECT '${COURT_ADMIN_ORG_ID}', '${COURT_ADMIN_PRINCIPAL_ID}',
       'namespace', gen_random_uuid(), 'allow',
       -- bits: blob.read|write|delete + doc.read|write|delete + acl.admin
       ((1::bigint << 0) | (1::bigint << 1) | (1::bigint << 2)
      | (1::bigint << 3) | (1::bigint << 4) | (1::bigint << 5)
      | (1::bigint << 7))
WHERE NOT EXISTS (
  SELECT 1 FROM quasar.acl_entry
  WHERE org_id = '${COURT_ADMIN_ORG_ID}' AND principal_id = '${COURT_ADMIN_PRINCIPAL_ID}'
);
SQL

echo "==> Minting admin JWT via /dev/token..."
mkdir -p secrets
TOKEN_JSON=$(curl --fail --silent \
  -X POST "http://127.0.0.1:${GRAVITON_HTTP_PORT}/dev/token" \
  -H 'Content-Type: application/json' \
  -d "{\"org_id\":\"${COURT_ADMIN_ORG_ID}\",\"principal_id\":\"${COURT_ADMIN_PRINCIPAL_ID}\",\"ttl_seconds\":86400}")
printf '%s\n' "$TOKEN_JSON" > ./secrets/admin-token.json
chmod 600 ./secrets/admin-token.json
TOKEN=$(printf '%s' "$TOKEN_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')

echo "==> Round-tripping a 64 KiB canary blob..."
CANARY=$(mktemp)
head -c 65536 /dev/urandom > "$CANARY"
BLOB_ID=$(curl --fail --silent \
  -X POST "http://127.0.0.1:${GRAVITON_HTTP_PORT}/api/blobs" \
  -H "Authorization: Bearer ${TOKEN}" \
  --data-binary "@${CANARY}" | tr -d '"')

ROUNDTRIP=$(mktemp)
curl --fail --silent -H "Authorization: Bearer ${TOKEN}" \
  "http://127.0.0.1:${GRAVITON_HTTP_PORT}/api/blobs/${BLOB_ID}" -o "$ROUNDTRIP"

if cmp -s "$CANARY" "$ROUNDTRIP"; then
  echo "    ✓ canary round-trip OK  (blob_id = ${BLOB_ID})"
  rm -f "$CANARY" "$ROUNDTRIP"
else
  echo "    ✗ canary round-trip MISMATCH — investigate before going live." >&2
  echo "      original : $CANARY"
  echo "      fetched  : $ROUNDTRIP"
  exit 1
fi

cat <<EOF

============================================================================
Pilot bootstrap complete.

Admin token (24h TTL) saved to deploy/on-prem/v1/secrets/admin-token.json.
Treat this file like a password — mode 0600, rotate before pilot closes.

Quick smoke test:
  TOKEN=\$(jq -r .access_token < deploy/on-prem/v1/secrets/admin-token.json)
  curl -H "Authorization: Bearer \$TOKEN" \\
       https://${GRAVITON_PUBLIC_HOST:-<hostname>}/api/blobs/<blob-id> -o out.bin

Next steps before sign-off:
  - Read COURT_DEPLOYMENT.md § "Pre-flight checklist" and tick every box.
  - Replace GRAVITON_SECURITY_DEV_SHARED_SECRET with an OIDC wire-up for
    the live deployment; delete the /dev/token handle from the Caddyfile.
  - Schedule the first audit-chain verification
    (SELECT * FROM quasar.verify_audit_chain_linkage('${COURT_ADMIN_ORG_ID}')).
============================================================================
EOF
