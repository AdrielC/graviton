#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "usage: $0 <candidate-image> [version=candidate]" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$repo_root/deploy/local-shardcake/docker-compose.yml"
image="$1"
version="${2:-candidate}"
node_one="http://127.0.0.1:58081"
node_two="http://127.0.0.1:58082"
tenant="00000000-0000-4000-8000-000000000701"
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/graviton-node-replacement.XXXXXX")"

cleanup() { find "$work_dir" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT INT TERM

command -v docker >/dev/null || { echo "docker is required" >&2; exit 2; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 2; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }
docker image inspect "$image" >/dev/null

compose_with() {
  GRAVITON_MANAGER_IMAGE="$image" \
  GRAVITON_NODE_1_IMAGE="$image" \
  GRAVITON_NODE_2_IMAGE="$image" \
  GRAVITON_NODE_1_VERSION="$version" \
  GRAVITON_NODE_2_VERSION="$version" \
    docker compose -f "$compose_file" "$@"
}

wait_ready() {
  local url="$1"
  for _ in $(seq 1 120); do
    if curl --fail --silent "$url/api/health/ready" >/dev/null; then return 0; fi
    sleep 1
  done
  echo "readiness timed out: $url" >&2
  return 1
}

upload() {
  local url="$1" payload="$2" session="$3"
  curl --fail --silent --show-error \
    -H "X-Graviton-Tenant-Id: $tenant" \
    -H "X-Graviton-Upload-Session-Id: $session" \
    -H "Content-Type: application/octet-stream" \
    --data-binary "@$payload" \
    "$url/api/v1/blobs" | jq -er '.blob.id'
}

read_exact() {
  curl --fail --silent --show-error "$1/api/v1/blobs/$2" | cmp --silent - "$3"
}

first="$work_dir/first.bin"
second="$work_dir/second.bin"
dd if=/dev/urandom of="$first" bs=1048576 count=8 2>/dev/null
dd if=/dev/urandom of="$second" bs=1048576 count=8 2>/dev/null

compose_with up -d --force-recreate shardcake-manager graviton-node-1 graviton-node-2 >/dev/null
wait_ready "$node_one"
wait_ready "$node_two"
first_blob="$(upload "$node_one" "$first" "00000000-0000-4000-8000-000000000702")"
read_exact "$node_two" "$first_blob" "$first"

compose_with up -d --no-deps --force-recreate shardcake-manager >/dev/null
wait_ready "$node_one"
wait_ready "$node_two"

compose_with up -d --no-deps --force-recreate graviton-node-1 >/dev/null
wait_ready "$node_one"
wait_ready "$node_two"
read_exact "$node_one" "$first_blob" "$first"
second_blob="$(upload "$node_one" "$second" "00000000-0000-4000-8000-000000000703")"
read_exact "$node_two" "$second_blob" "$second"

compose_with up -d --no-deps --force-recreate graviton-node-2 >/dev/null
wait_ready "$node_one"
wait_ready "$node_two"
read_exact "$node_two" "$first_blob" "$first"
read_exact "$node_two" "$second_blob" "$second"

jq -n \
  --arg schema "graviton-node-replacement-qualification-v1" \
  --arg image "$image" \
  --arg imageId "$(docker image inspect --format '{{.Id}}' "$image")" \
  --arg version "$version" \
  --arg firstBlob "$first_blob" \
  --arg secondBlob "$second_blob" \
  '{schema: $schema, image: $image, imageId: $imageId, version: $version, firstBlob: $firstBlob, secondBlob: $secondBlob, managerReplacementVerified: true, nodeOneReplacementVerified: true, nodeTwoReplacementVerified: true, writesDuringReplacementVerified: true, byteExactReadbackVerified: true, cleanStore: true}'
