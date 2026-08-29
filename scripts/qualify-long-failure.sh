#!/usr/bin/env bash
set -euo pipefail

if [[ $# -gt 2 ]]; then
  echo "usage: $0 [duration-seconds=900] [payload-bytes=4194304]" >&2
  exit 2
fi

duration_seconds="${1:-900}"
payload_bytes="${2:-4194304}"
[[ "$duration_seconds" =~ ^[1-9][0-9]*$ && "$duration_seconds" -ge 60 ]] || { echo "duration must be at least 60 seconds" >&2; exit 2; }
[[ "$payload_bytes" =~ ^[1-9][0-9]*$ ]] || { echo "payload bytes must be positive" >&2; exit 2; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$repo_root/deploy/local-shardcake/docker-compose.yml"
node_urls=("http://127.0.0.1:58081" "http://127.0.0.1:58082")
tenant_id="00000000-0000-4000-8000-000000000801"
part_bytes=1048576
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/graviton-long-failure.XXXXXX")"
payload="$work_dir/payload.bin"
stop_file="$work_dir/stop"
result_file="$work_dir/workload-result"
response_file="$work_dir/response.json"
download_file="$work_dir/download.bin"
part_file="$work_dir/part.bin"
restored_services=()

compose() { docker compose -f "$compose_file" "$@"; }

cleanup() {
  touch "$stop_file" 2>/dev/null || true
  for service in "${restored_services[@]}"; do
    compose start "$service" >/dev/null 2>&1 || true
  done
  find "$work_dir" -depth -delete 2>/dev/null || true
}
trap cleanup EXIT INT TERM

command -v docker >/dev/null || { echo "docker is required" >&2; exit 2; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 2; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }
command -v uuidgen >/dev/null || { echo "uuidgen is required" >&2; exit 2; }

wait_ready() {
  local base_url="$1"
  for _ in $(seq 1 120); do
    if curl --fail --silent --connect-timeout 2 --max-time 5 "$base_url/api/health/ready" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "readiness timed out: $base_url" >&2
  return 1
}

request_code() {
  local output="$1"
  shift
  local code
  code="$(curl --silent --show-error --connect-timeout 3 --max-time 30 --output "$output" --write-out '%{http_code}' "$@" 2>/dev/null || true)"
  printf '%s' "${code:-000}"
}

workload() {
  local successes=0
  local retries=0
  local failures=0
  local node_cursor=0

  while [[ ! -f "$stop_file" ]]; do
    local upload_id
    upload_id="$(uuidgen | tr '[:upper:]' '[:lower:]')"
    local created=false

    for _ in $(seq 1 120); do
      local base_url="${node_urls[$((node_cursor % 2))]}"
      node_cursor=$((node_cursor + 1))
      local code
      code="$(request_code "$response_file" \
        -X POST \
        -H "X-Graviton-Tenant-Id: $tenant_id" \
        -H "X-Graviton-Upload-Session-Id: $upload_id" \
        -H "Upload-Length: $payload_bytes" \
        -H "Content-Type: application/octet-stream" \
        "$base_url/api/v1/uploads")"
      if [[ "$code" == "201" || "$code" == "409" ]]; then
        created=true
        break
      fi
      retries=$((retries + 1))
      sleep 1
    done
    if [[ "$created" != true ]]; then
      failures=$((failures + 1))
      break
    fi

    local offset=0
    local upload_failed=false
    while ((offset < payload_bytes)); do
      local part_index=$((offset / part_bytes))
      dd if="$payload" of="$part_file" bs="$part_bytes" skip="$part_index" count=1 2>/dev/null
      local count
      count="$(wc -c < "$part_file" | tr -d ' ')"
      local part_id
      part_id="$(uuidgen | tr '[:upper:]' '[:lower:]')"
      local advanced=false

      for _ in $(seq 1 120); do
        local base_url="${node_urls[$((node_cursor % 2))]}"
        node_cursor=$((node_cursor + 1))
        local code
        code="$(request_code "$response_file" \
          -X PATCH \
          -H "X-Graviton-Tenant-Id: $tenant_id" \
          -H "Upload-Offset: $offset" \
          -H "Upload-Part-Id: $part_id" \
          -H "Content-Length: $count" \
          --data-binary "@$part_file" \
          "$base_url/api/v1/uploads/$upload_id")"
        if [[ "$code" == "204" ]]; then
          offset=$((offset + count))
          advanced=true
          break
        fi

        local status_code
        status_code="$(request_code "$response_file" \
          -H "X-Graviton-Tenant-Id: $tenant_id" \
          "$base_url/api/v1/uploads/$upload_id")"
        if [[ "$status_code" == "200" ]]; then
          local observed
          observed="$(jq -er '.offset' "$response_file" 2>/dev/null || true)"
          if [[ "$observed" =~ ^[0-9]+$ && "$observed" -gt "$offset" ]]; then
            offset="$observed"
            advanced=true
            break
          fi
        fi
        retries=$((retries + 1))
        sleep 1
      done
      if [[ "$advanced" != true ]]; then
        upload_failed=true
        break
      fi
    done
    if [[ "$upload_failed" == true ]]; then
      failures=$((failures + 1))
      break
    fi

    local blob_id=""
    for _ in $(seq 1 120); do
      local base_url="${node_urls[$((node_cursor % 2))]}"
      node_cursor=$((node_cursor + 1))
      local code
      code="$(request_code "$response_file" \
        -X POST \
        -H "X-Graviton-Tenant-Id: $tenant_id" \
        "$base_url/api/v1/uploads/$upload_id/commit")"
      if [[ "$code" == "200" ]]; then
        blob_id="$(jq -er '.committedBlob' "$response_file" 2>/dev/null || true)"
      else
        local status_code
        status_code="$(request_code "$response_file" \
          -H "X-Graviton-Tenant-Id: $tenant_id" \
          "$base_url/api/v1/uploads/$upload_id")"
        if [[ "$status_code" == "200" && "$(jq -r '.state' "$response_file")" == "Committed" ]]; then
          blob_id="$(jq -er '.committedBlob' "$response_file" 2>/dev/null || true)"
        fi
      fi
      if [[ -n "$blob_id" && "$blob_id" != "null" ]]; then break; fi
      retries=$((retries + 1))
      sleep 1
    done
    if [[ -z "$blob_id" || "$blob_id" == "null" ]]; then
      failures=$((failures + 1))
      break
    fi

    local verified=false
    for _ in $(seq 1 120); do
      local base_url="${node_urls[$((node_cursor % 2))]}"
      node_cursor=$((node_cursor + 1))
      if curl --fail --silent --show-error --connect-timeout 3 --max-time 30 \
        "$base_url/api/v1/blobs/$blob_id" > "$download_file" 2>/dev/null \
        && cmp --silent "$download_file" "$payload"; then
        verified=true
        break
      fi
      retries=$((retries + 1))
      sleep 1
    done
    if [[ "$verified" != true ]]; then
      failures=$((failures + 1))
      break
    fi
    successes=$((successes + 1))
  done

  printf '%s %s %s\n' "$successes" "$retries" "$failures" > "$result_file"
}

dd if=/dev/urandom of="$payload" bs="$payload_bytes" count=1 2>/dev/null
wait_ready "${node_urls[0]}"
wait_ready "${node_urls[1]}"

started_at="$(date +%s)"
deadline=$((started_at + duration_seconds))
fault_cycles=0
workload &
workload_pid=$!

while (( $(date +%s) < deadline )); do
  compose stop graviton-node-1 >/dev/null
  restored_services+=(graviton-node-1)
  sleep 5
  wait_ready "${node_urls[1]}"
  compose start graviton-node-1 >/dev/null
  restored_services=()
  wait_ready "${node_urls[0]}"

  compose stop graviton-node-2 >/dev/null
  restored_services+=(graviton-node-2)
  sleep 5
  wait_ready "${node_urls[0]}"
  compose start graviton-node-2 >/dev/null
  restored_services=()
  wait_ready "${node_urls[1]}"

  compose restart shardcake-manager >/dev/null
  wait_ready "${node_urls[0]}"
  wait_ready "${node_urls[1]}"

  compose stop minio >/dev/null
  restored_services+=(minio)
  sleep 10
  compose start minio >/dev/null
  restored_services=()
  wait_ready "${node_urls[0]}"
  wait_ready "${node_urls[1]}"

  compose stop postgres >/dev/null
  restored_services+=(postgres)
  sleep 10
  compose start postgres >/dev/null
  restored_services=()
  wait_ready "${node_urls[0]}"
  wait_ready "${node_urls[1]}"

  fault_cycles=$((fault_cycles + 1))
done

touch "$stop_file"
wait "$workload_pid"
read -r successes retries failures < "$result_file"
ended_at="$(date +%s)"

((successes >= 1)) || { echo "failure drill completed no verified uploads" >&2; exit 1; }
((failures == 0)) || { echo "failure drill had $failures unrecovered workload failure(s)" >&2; exit 1; }

jq -n \
  --arg schema "graviton-long-failure-qualification-v1" \
  --argjson requestedDurationSeconds "$duration_seconds" \
  --argjson observedDurationSeconds "$((ended_at - started_at))" \
  --argjson payloadBytes "$payload_bytes" \
  --argjson successfulRoundTrips "$successes" \
  --argjson recoveredRetries "$retries" \
  --argjson unrecoveredFailures "$failures" \
  --argjson faultCycles "$fault_cycles" \
  '{schema: $schema, requestedDurationSeconds: $requestedDurationSeconds, observedDurationSeconds: $observedDurationSeconds, payloadBytes: $payloadBytes, successfulRoundTrips: $successfulRoundTrips, recoveredRetries: $recoveredRetries, unrecoveredFailures: $unrecoveredFailures, faultCycles: $faultCycles, faults: ["node-1-stop", "node-2-stop", "manager-restart", "object-store-outage", "postgres-outage"], resumableUploadVerified: true, byteExactReadbackVerified: true}'
