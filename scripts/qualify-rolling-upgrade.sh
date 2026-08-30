#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 4 ]]; then
  echo "usage: $0 <baseline-image> <candidate-image> [baseline-version=baseline] [candidate-version=candidate]" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$repo_root/deploy/local-shardcake/docker-compose.yml"
baseline_image="$1"
candidate_image="$2"
baseline_version="${3:-baseline}"
candidate_version="${4:-candidate}"
manifest_integrity_mode="${GRAVITON_MANIFEST_INTEGRITY_REQUIRED:-false}"
node_one="http://127.0.0.1:58081"
node_two="http://127.0.0.1:58082"

[[ "$manifest_integrity_mode" == "true" || "$manifest_integrity_mode" == "false" ]] || {
  echo "GRAVITON_MANIFEST_INTEGRITY_REQUIRED must be true or false" >&2
  exit 2
}

command -v docker >/dev/null || { echo "docker is required" >&2; exit 2; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 2; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }
docker image inspect "$baseline_image" >/dev/null
docker image inspect "$candidate_image" >/dev/null

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/graviton-rolling-upgrade.XXXXXX")"
cleanup() { find "$work_dir" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT INT TERM

compose_with() {
  local manager_image="$1"
  local node_one_image="$2"
  local node_two_image="$3"
  local node_one_version="$4"
  local node_two_version="$5"
  shift 5
  GRAVITON_MANAGER_IMAGE="$manager_image" \
  GRAVITON_NODE_1_IMAGE="$node_one_image" \
  GRAVITON_NODE_2_IMAGE="$node_two_image" \
  GRAVITON_NODE_1_VERSION="$node_one_version" \
  GRAVITON_NODE_2_VERSION="$node_two_version" \
    docker compose -f "$compose_file" "$@"
}

wait_ready() {
  local base_url="$1"
  for _ in $(seq 1 120); do
    if curl --fail --silent "$base_url/api/health/ready" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "readiness timed out: $base_url" >&2
  return 1
}

upload() {
  local base_url="$1"
  local payload="$2"
  local session_id="$3"
  curl --fail --silent --show-error \
    -H "X-Graviton-Tenant-Id: 00000000-0000-4000-8000-000000000701" \
    -H "X-Graviton-Upload-Session-Id: $session_id" \
    -H "Content-Type: application/octet-stream" \
    --data-binary "@$payload" \
    "$base_url/api/v1/blobs" | jq -er '.blob.id'
}

read_exact() {
  local base_url="$1"
  local blob_id="$2"
  local payload="$3"
  curl --fail --silent --show-error "$base_url/api/v1/blobs/$blob_id" | cmp --silent - "$payload"
}

payload_baseline="$work_dir/baseline.bin"
payload_candidate="$work_dir/candidate.bin"
payload_rollback="$work_dir/rollback.bin"
dd if=/dev/urandom of="$payload_baseline" bs=1048576 count=8 2>/dev/null
dd if=/dev/urandom of="$payload_candidate" bs=1048576 count=8 2>/dev/null
dd if=/dev/urandom of="$payload_rollback" bs=1048576 count=8 2>/dev/null

# Cold baseline cohort.
compose_with "$baseline_image" "$baseline_image" "$baseline_image" "$baseline_version" "$baseline_version" \
  up -d --force-recreate shardcake-manager graviton-node-1 graviton-node-2 >/dev/null
wait_ready "$node_one"
wait_ready "$node_two"
baseline_blob="$(upload "$node_one" "$payload_baseline" "00000000-0000-4000-8000-000000000702")"
read_exact "$node_two" "$baseline_blob" "$payload_baseline"

# Manager first, then one mixed-version data node.
compose_with "$candidate_image" "$baseline_image" "$baseline_image" "$baseline_version" "$baseline_version" \
  up -d --no-deps --force-recreate shardcake-manager >/dev/null
compose_with "$candidate_image" "$candidate_image" "$baseline_image" "$candidate_version" "$baseline_version" \
  up -d --no-deps --force-recreate graviton-node-1 >/dev/null
wait_ready "$node_one"
wait_ready "$node_two"
read_exact "$node_one" "$baseline_blob" "$payload_baseline"
candidate_blob="$(upload "$node_one" "$payload_candidate" "00000000-0000-4000-8000-000000000703")"
read_exact "$node_two" "$candidate_blob" "$payload_candidate"

# Complete the candidate cohort.
compose_with "$candidate_image" "$candidate_image" "$candidate_image" "$candidate_version" "$candidate_version" \
  up -d --no-deps --force-recreate graviton-node-2 >/dev/null
wait_ready "$node_one"
wait_ready "$node_two"
read_exact "$node_two" "$baseline_blob" "$payload_baseline"
read_exact "$node_two" "$candidate_blob" "$payload_candidate"

# Roll one node back against the candidate-written database and object store.
compose_with "$candidate_image" "$baseline_image" "$candidate_image" "$baseline_version" "$candidate_version" \
  up -d --no-deps --force-recreate graviton-node-1 >/dev/null
wait_ready "$node_one"
wait_ready "$node_two"
read_exact "$node_one" "$candidate_blob" "$payload_candidate"
rollback_blob="$(upload "$node_one" "$payload_rollback" "00000000-0000-4000-8000-000000000704")"
read_exact "$node_two" "$rollback_blob" "$payload_rollback"

# Leave the topology fully upgraded.
compose_with "$candidate_image" "$candidate_image" "$candidate_image" "$candidate_version" "$candidate_version" \
  up -d --no-deps --force-recreate graviton-node-1 >/dev/null
wait_ready "$node_one"
wait_ready "$node_two"
read_exact "$node_one" "$rollback_blob" "$payload_rollback"

jq -n \
  --arg schema "graviton-rolling-upgrade-qualification-v1" \
  --arg baselineImage "$baseline_image" \
  --arg baselineImageId "$(docker image inspect --format '{{.Id}}' "$baseline_image")" \
  --arg candidateImage "$candidate_image" \
  --arg candidateImageId "$(docker image inspect --format '{{.Id}}' "$candidate_image")" \
  --arg manifestIntegrityRequired "$manifest_integrity_mode" \
  --arg baselineBlob "$baseline_blob" \
  --arg candidateBlob "$candidate_blob" \
  --arg rollbackBlob "$rollback_blob" \
  '{schema: $schema, baselineImage: $baselineImage, baselineImageId: $baselineImageId, candidateImage: $candidateImage, candidateImageId: $candidateImageId, manifestIntegrityRequired: ($manifestIntegrityRequired == "true"), baselineBlob: $baselineBlob, candidateBlob: $candidateBlob, rollbackBlob: $rollbackBlob, mixedVersionReadWriteVerified: true, rollbackReadWriteVerified: true, finalCandidateCohortReady: true}'
