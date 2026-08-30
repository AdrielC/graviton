#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$repo_root/deploy/three-domain/docker-compose.yml"
base_url="http://127.0.0.1:58181"
prometheus_url="http://127.0.0.1:59090"
grafana_url="http://127.0.0.1:59300"
project_name="graviton-three-domain"
lost_volume="${project_name}_minio-b-data"

compose() {
  docker compose -f "$compose_file" "$@"
}

wait_ready() {
  local url="$1"
  for _ in $(seq 1 90); do
    if curl --fail --silent "$url" >/dev/null; then return 0; fi
    sleep 2
  done
  printf 'readiness timed out: %s\n' "$url" >&2
  return 1
}

sha256_file() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'
  fi
}

object_count() {
  compose --profile operator run --rm --no-deps minio-client \
    'mc alias set b http://minio-b:9000 graviton graviton-three-domain-secret >/dev/null && mc find b/graviton-blocks-b --type f | wc -l' \
    | tr -d '[:space:]'
}

for command in docker curl jq python3; do command -v "$command" >/dev/null || { echo "$command is required" >&2; exit 2; }; done
compose ps --status running --services | grep -qx graviton || {
  echo "the three-domain topology must already be running; use ./scripts/demo-three-domain.sh up" >&2
  exit 1
}

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/graviton-three-domain.XXXXXX")"
cleanup() { find "$work_dir" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT INT TERM

payload="$work_dir/payload.bin"
download="$work_dir/download.bin"
stage() { printf 'qualification stage: %s\n' "$1" >&2; }

stage "upload fresh and duplicate payloads"
dd if=/dev/urandom of="$payload" bs=1048576 count=32 2>/dev/null
payload_sha="$(sha256_file "$payload")"

headers=(-H "Content-Type: application/octet-stream" -H "Content-Length: $(wc -c < "$payload" | tr -d ' ')")
first="$(curl --fail --silent --show-error "${headers[@]}" --data-binary "@$payload" "$base_url/api/v1/blobs")"
second="$(curl --fail --silent --show-error "${headers[@]}" --data-binary "@$payload" "$base_url/api/v1/blobs")"
blob_id="$(jq -r '.blob.id' <<<"$first")"
fresh_blocks="$(jq -r '.freshBlocks' <<<"$first")"
jq -e '.freshBlocks > 0 and .duplicateBlocks == 0' <<<"$first" >/dev/null
jq -e '.freshBlocks == 0 and .duplicateBlocks > 0' <<<"$second" >/dev/null
curl --fail --silent --show-error "$base_url/api/v1/blobs/$blob_id" > "$download"
[[ "$(sha256_file "$download")" == "$payload_sha" ]]

# Kill the preferred local target. Readiness and byte-exact reconstruction must
# continue from the two remote domains.
stage "stop preferred local target and reconstruct remotely"
compose stop minio-a >/dev/null
wait_ready "$base_url/api/health/ready"
remote_read_started="$(python3 -c 'import time; print(time.time_ns())')"
curl --fail --silent --show-error --max-time 15 "$base_url/api/v1/blobs/$blob_id" | cmp --silent - "$payload"
remote_read_ended="$(python3 -c 'import time; print(time.time_ns())')"
metrics_during_loss="$(curl --fail --silent --show-error "$base_url/metrics")"
grep -q 'graviton_erasure_shard_reads_total.*locality="remote"' <<<"$metrics_during_loss"
grep -q 'graviton_erasure_reconstructions_total' <<<"$metrics_during_loss"
compose start minio-a >/dev/null
wait_ready "http://127.0.0.1:59100/minio/health/ready"
wait_ready "$base_url/api/health/ready"

# Remove the entire second target volume, recreate an empty endpoint, and wait
# for the manifest-driven scrub to regenerate its missing shards.
stage "destroy and recreate target-b volume"
volume_before="$(docker volume inspect "$lost_volume" --format '{{.CreatedAt}}')"
repair_started="$(python3 -c 'import time; print(time.time_ns())')"
compose stop minio-b >/dev/null
compose rm -f minio-b minio-init >/dev/null
docker volume rm "$lost_volume" >/dev/null
sleep 1
compose up -d minio-b minio-init >/dev/null
for _ in $(seq 1 90); do
  if [[ "$(object_count 2>/dev/null || printf 0)" -ge "$fresh_blocks" ]]; then break; fi
  sleep 2
done
repaired_objects="$(object_count)"
repair_ended="$(python3 -c 'import time; print(time.time_ns())')"
[[ "$repaired_objects" -ge "$fresh_blocks" ]]
volume_after="$(docker volume inspect "$lost_volume" --format '{{.CreatedAt}}')"
[[ "$volume_before" != "$volume_after" ]]
curl --fail --silent --show-error "$base_url/api/v1/blobs/$blob_id" | cmp --silent - "$payload"

metrics_after_repair="$(curl --fail --silent --show-error "$base_url/metrics")"
grep -q 'graviton_erasure_repairs_total.*outcome="repaired"' <<<"$metrics_after_repair"
grep -Eq 'graviton_replica_under_protected_blocks(\{[^}]*\})? 0(\.0)?' <<<"$metrics_after_repair"

stage "verify live Prometheus rules and Grafana dashboard"
wait_ready "$prometheus_url/-/ready"
rules="$(curl --fail --silent --show-error "$prometheus_url/api/v1/rules")"
jq -e '.status == "success" and ([.data.groups[].rules[].name] | index("GravitonRepairNotConverged") != null)' <<<"$rules" >/dev/null
query="$(curl --fail --silent --show-error --get --data-urlencode 'query=up{job="graviton"}' "$prometheus_url/api/v1/query")"
jq -e '.status == "success" and .data.result[0].value[1] == "1"' <<<"$query" >/dev/null
wait_ready "$grafana_url/api/health"
dashboard="$(curl --fail --silent --show-error "$grafana_url/api/dashboards/uid/graviton-slo")"
jq -e '.dashboard.uid == "graviton-slo" and (.dashboard.panels | length) == 6' <<<"$dashboard" >/dev/null

jq -n \
  --arg schema "graviton-three-domain-qualification-v1" \
  --arg revision "$(git -C "$repo_root" rev-parse HEAD)" \
  --arg blobId "$blob_id" \
  --arg payloadSha256 "$payload_sha" \
  --arg volumeCreatedBefore "$volume_before" \
  --arg volumeCreatedAfter "$volume_after" \
  --argjson freshBlocks "$fresh_blocks" \
  --argjson repairedObjects "$repaired_objects" \
  --argjson remoteReadSeconds "$(python3 -c "print(($remote_read_ended - $remote_read_started) / 1_000_000_000)")" \
  --argjson repairConvergenceSeconds "$(python3 -c "print(($repair_ended - $repair_started) / 1_000_000_000)")" \
  '{schema: $schema, revision: $revision, blobId: $blobId, payloadSha256: $payloadSha256,
    storageMode: "xor-2-1-v1", independentEndpoints: 3, declaredFailureDomains: 3,
    freshBlocks: $freshBlocks, localTargetProcessStopped: true, remoteReconstructionVerified: true,
    remoteReadSeconds: $remoteReadSeconds,
    destroyedTargetVolume: true, volumeCreatedBefore: $volumeCreatedBefore, volumeCreatedAfter: $volumeCreatedAfter,
    repairedObjects: $repairedObjects, repairConvergenceSeconds: $repairConvergenceSeconds,
    repairConverged: true, byteExactReadbackVerified: true,
    prometheusScrapeVerified: true, sloRulesLoaded: true, grafanaDashboardProvisioned: true}'
