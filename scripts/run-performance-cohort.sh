#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "usage: $0 <image> <revision> <payload-file> <output-directory>" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$repo_root/deploy/local-shardcake/docker-compose.yml"
image="$1"
revision="$2"
payload="$3"
output_dir="$4"
node_one="http://127.0.0.1:58081"
node_two="http://127.0.0.1:58082"

[[ -f "$payload" ]] || { echo "payload not found: $payload" >&2; exit 1; }
docker image inspect "$image" >/dev/null
mkdir -p "$output_dir"

compose_with_image() {
  GRAVITON_MANAGER_IMAGE="$image" \
  GRAVITON_NODE_1_IMAGE="$image" \
  GRAVITON_NODE_2_IMAGE="$image" \
  GRAVITON_NODE_1_VERSION="$revision" \
  GRAVITON_NODE_2_VERSION="$revision" \
    docker compose -f "$compose_file" "$@"
}

monitor_pid=""
cleanup() {
  if [[ -n "$monitor_pid" ]] && kill -0 "$monitor_pid" 2>/dev/null; then
    kill -TERM "$monitor_pid" 2>/dev/null || true
    wait "$monitor_pid" 2>/dev/null || true
  fi
  compose_with_image down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

compose_with_image down -v --remove-orphans >/dev/null 2>&1 || true
compose_with_image up -d --no-build
for _ in $(seq 1 120); do
  if curl --fail --silent "$node_one/api/health/ready" >/dev/null \
    && curl --fail --silent "$node_two/api/health/ready" >/dev/null; then
    break
  fi
  sleep 1
done
curl --fail --silent "$node_one/api/health/ready" >/dev/null
curl --fail --silent "$node_two/api/health/ready" >/dev/null

"$repo_root/scripts/monitor-performance-telemetry.py" \
  "$output_dir/telemetry.json" \
  "$node_one/metrics" \
  "$node_two/metrics" &
monitor_pid=$!

GRAVITON_BENCHMARK_REVISION="$revision" \
GRAVITON_BENCHMARK_REPOSITORY_DIRTY=false \
GRAVITON_BENCHMARK_BACKEND_DESCRIPTION="two-node Shardcake, PostgreSQL, MinIO, image $image" \
  "$repo_root/scripts/benchmark-tenant-mix.sh" \
    "$node_one" \
    "$payload" \
    "${GRAVITON_GATE_TENANTS:-4}" \
    "${GRAVITON_GATE_SAMPLES_PER_TENANT:-4}" \
    "$output_dir/workload" \
    > "$output_dir/workload.json"

kill -TERM "$monitor_pid"
wait "$monitor_pid"
monitor_pid=""
curl --fail --silent "$node_one/metrics" > "$output_dir/node-1-final.prom"
curl --fail --silent "$node_two/metrics" > "$output_dir/node-2-final.prom"

jq -n \
  --arg schema "graviton-performance-cohort-v1" \
  --arg image "$image" \
  --arg imageId "$(docker image inspect --format '{{.Id}}' "$image")" \
  --arg revision "$revision" \
  --slurpfile workload "$output_dir/workload/summary.json" \
  --slurpfile telemetry "$output_dir/telemetry.json" \
  '{schema: $schema, image: $image, imageId: $imageId, revision: $revision, workload: $workload[0], telemetry: $telemetry[0]}' \
  > "$output_dir/cohort.json"
