#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$repo_root/deploy/local-shardcake/docker-compose.yml"
node_one="http://127.0.0.1:58081"
node_two="http://127.0.0.1:58082"
tenant_id="00000000-0000-4000-8000-000000000601"
session_id="00000000-0000-4000-8000-000000000602"
started_services=()

compose() {
  docker compose -f "$compose_file" "$@"
}

cleanup() {
  if ((${#started_services[@]} > 0)); then
    for service in "${started_services[@]}"; do
      compose start "$service" >/dev/null 2>&1 || true
    done
  fi
}
trap cleanup EXIT INT TERM

wait_ready() {
  local base_url="$1"
  for _ in $(seq 1 90); do
    if curl --fail --silent "$base_url/api/health/ready" >/dev/null; then
      return 0
    fi
    sleep 2
  done
  echo "readiness timed out: $base_url" >&2
  return 1
}

wait_not_ready() {
  local base_url="$1"
  for _ in $(seq 1 30); do
    local status
    status="$(curl --silent --output /dev/null --write-out '%{http_code}' "$base_url/api/health/ready" || true)"
    if [[ "$status" == "503" || "$status" == "000" ]]; then
      return 0
    fi
    sleep 1
  done
  echo "expected readiness to fail: $base_url" >&2
  return 1
}

sha256_file() {
  if command -v sha256sum >/dev/null; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

command -v docker >/dev/null || { echo "docker is required" >&2; exit 2; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 2; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }

compose ps --status running --services | grep -qx graviton-node-1 || {
  echo "the local Shardcake topology must already be running; use ./scripts/demo-shardcake-local.sh up" >&2
  exit 1
}
compose ps --status running --services | grep -qx graviton-node-2 || {
  echo "graviton-node-2 is not running" >&2
  exit 1
}

# Recreate the manager and both nodes as one cold-start cohort. Nodes may reach
# the manager before their control endpoints are ready, so this is a permanent
# regression gate for bounded registration retry and startup convergence.
compose up -d --force-recreate shardcake-manager graviton-node-1 graviton-node-2 >/dev/null
wait_ready "$node_one"
wait_ready "$node_two"

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/graviton-failure-proof.XXXXXX")"
remove_work_dir() { find "$work_dir" -depth -delete 2>/dev/null || true; }
trap 'cleanup; remove_work_dir' EXIT INT TERM
payload="$work_dir/payload.bin"
download="$work_dir/download.bin"
interrupted="$work_dir/interrupted.bin"
dd if=/dev/urandom of="$payload" bs=1m count=8 2>/dev/null
dd if=/dev/urandom of="$interrupted" bs=1m count=64 2>/dev/null

headers=(
  -H "X-Graviton-Tenant-Id: $tenant_id"
  -H "X-Graviton-Upload-Session-Id: $session_id"
  -H "Content-Type: application/octet-stream"
  -H "Content-Length: $(wc -c < "$payload" | tr -d ' ')"
)

first="$(curl --fail --silent --show-error "${headers[@]}" --data-binary "@$payload" "$node_one/api/v1/blobs")"
second="$(curl --fail --silent --show-error "${headers[@]}" --data-binary "@$payload" "$node_two/api/v1/blobs")"
blob_id="$(jq -r '.blob.id' <<<"$first")"
jq -e '.freshBlocks > 0 and .duplicateBlocks == 0' <<<"$first" >/dev/null
jq -e '.freshBlocks == 0 and .duplicateBlocks > 0' <<<"$second" >/dev/null

curl --fail --silent --show-error "$node_two/api/v1/blobs/$blob_id" > "$download"
[[ "$(sha256_file "$payload")" == "$(sha256_file "$download")" ]]

inventory_before="$(curl --fail --silent --show-error "$node_two/api/v1/blobs" | jq -S .)"
interrupted_length="$(wc -c < "$interrupted" | tr -d ' ')"
curl --fail --silent --show-error --limit-rate 256k \
  -H "Content-Type: application/octet-stream" \
  -H "Content-Length: $interrupted_length" \
  --data-binary "@$interrupted" "$node_two/api/v1/blobs" > "$work_dir/interrupted-response.json" &
upload_pid=$!
sleep 2
kill "$upload_pid" 2>/dev/null || true
wait "$upload_pid" 2>/dev/null || true
inventory_after="$(curl --fail --silent --show-error "$node_two/api/v1/blobs" | jq -S .)"
[[ "$inventory_before" == "$inventory_after" ]] || {
  echo "an interrupted upload published a manifest" >&2
  exit 1
}

compose stop graviton-node-1 >/dev/null
started_services+=(graviton-node-1)
wait_ready "$node_two"
curl --fail --silent --show-error "$node_two/api/v1/blobs/$blob_id" | cmp --silent - "$payload"

compose stop minio >/dev/null
started_services+=(minio)
wait_not_ready "$node_two"
compose start minio >/dev/null
started_services=(graviton-node-1)
wait_ready "$node_two"
curl --fail --silent --show-error "$node_two/api/v1/blobs/$blob_id" | cmp --silent - "$payload"

compose stop postgres >/dev/null
started_services+=(postgres)
wait_not_ready "$node_two"
compose start postgres >/dev/null
started_services=(graviton-node-1)
wait_ready "$node_two"

compose start graviton-node-1 >/dev/null
started_services=()
wait_ready "$node_one"
wait_ready "$node_two"

jq -n \
  --arg schema "graviton-local-failure-qualification-v1" \
  --arg blobId "$blob_id" \
  --arg payloadSha256 "$(sha256_file "$payload")" \
  '{schema: $schema, blobId: $blobId, payloadSha256: $payloadSha256, duplicateRouteVerified: true, interruptedUploadUnpublished: true, nodeReassignmentVerified: true, objectStoreReadinessVerified: true, manifestStoreReadinessVerified: true, byteExactReadbackVerified: true}'
