#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 5 ]]; then
  echo "usage: $0 <base-url> <payload-file> [tenants=4] [samples-per-tenant=4] [output-directory=tenant-benchmark]" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
base_url="${1%/}"
payload="$2"
tenants="${3:-4}"
samples_per_tenant="${4:-4}"
output_dir="${5:-tenant-benchmark}"

[[ -f "$payload" ]] || { echo "payload not found: $payload" >&2; exit 1; }
[[ "$tenants" =~ ^[1-9][0-9]*$ ]] || { echo "tenants must be positive" >&2; exit 2; }
[[ "$samples_per_tenant" =~ ^[1-9][0-9]*$ ]] || { echo "samples-per-tenant must be positive" >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 2; }

mkdir -p "$output_dir/raw"
output_dir="$(cd "$output_dir" && pwd)"

tenant_uuid() {
  printf '00000000-0000-4000-8000-%012x' "$1"
}

session_uuid() {
  printf '00000000-0000-4000-8000-%012x' "$((0x100000 + $1 * 4096 + $2))"
}

run_one() {
  local tenant_number="$1"
  local sample_number="$2"
  local requested_destination="${3:-}"
  local tenant_id session_id destination
  tenant_id="$(tenant_uuid "$tenant_number")"
  session_id="$(session_uuid "$tenant_number" "$sample_number")"
  destination="${requested_destination:-$output_dir/raw/tenant-$(printf '%03d' "$tenant_number")-sample-$(printf '%04d' "$sample_number").json}"
  GRAVITON_BENCHMARK_TENANT_ID="$tenant_id" \
  GRAVITON_BENCHMARK_SESSION_ID="$session_id" \
  GRAVITON_BENCHMARK_MEDIA_TYPE="${GRAVITON_BENCHMARK_MEDIA_TYPE:-application/octet-stream}" \
    "$repo_root/scripts/benchmark-http.sh" "$base_url" "$payload" > "$destination"
}

for ((tenant = 1; tenant <= tenants; tenant++)); do
  run_one "$tenant" 0 /dev/null
done

start_ns="$(python3 -c 'import time; print(time.monotonic_ns())')"
declare -a pids
failures=0
for ((sample = 1; sample <= samples_per_tenant; sample++)); do
  worker=0
  pids=()
  for ((tenant = 1; tenant <= tenants; tenant++)); do
    worker=$((worker + 1))
    run_one "$tenant" "$sample" &
    pids[$worker]=$!
  done
  for ((index = 1; index <= worker; index++)); do
    if ! wait "${pids[$index]}"; then failures=$((failures + 1)); fi
  done
done
end_ns="$(python3 -c 'import time; print(time.monotonic_ns())')"

if ((failures > 0)); then
  echo "$failures tenant benchmark sample(s) failed" >&2
  exit 1
fi

python3 - "$output_dir" "$tenants" "$samples_per_tenant" "$start_ns" "$end_ns" <<'PY'
import json
import math
import pathlib
import statistics
import sys

output = pathlib.Path(sys.argv[1])
tenant_count = int(sys.argv[2])
samples_per_tenant = int(sys.argv[3])
elapsed_seconds = (int(sys.argv[5]) - int(sys.argv[4])) / 1_000_000_000
paths = sorted((output / "raw").glob("tenant-*-sample-*.json"))
expected = tenant_count * samples_per_tenant
if len(paths) != expected:
    raise SystemExit(f"expected {expected} raw samples, found {len(paths)}")

rows = [json.loads(path.read_text()) for path in paths]
for row in rows:
    if row.get("schema") != "graviton-benchmark-v1" or row.get("verified") is not True:
        raise SystemExit("invalid or unverified tenant benchmark sample")
for field in ("revision", "backendDescription", "bytes", "payloadSha256"):
    values = {json.dumps(row.get(field), sort_keys=True) for row in rows}
    if len(values) != 1:
        raise SystemExit(f"tenant benchmark samples disagree on {field}")

def percentile(values, probability):
    ordered = sorted(float(value) for value in values)
    return ordered[max(0, math.ceil(probability * len(ordered)) - 1)]

def distribution(values):
    numeric = [float(value) for value in values]
    return {
        "min": min(numeric),
        "mean": statistics.fmean(numeric),
        "p50": percentile(numeric, 0.50),
        "p95": percentile(numeric, 0.95),
        "p99": percentile(numeric, 0.99),
        "max": max(numeric),
    }

per_tenant = {}
tenant_rates = []
for tenant in range(1, tenant_count + 1):
    prefix = f"tenant-{tenant:03d}-"
    selected = [json.loads(path.read_text()) for path in paths if path.name.startswith(prefix)]
    rate = statistics.median(float(row["uploadMiBPerSecond"]) for row in selected)
    tenant_rates.append(rate)
    per_tenant[f"{tenant:03d}"] = {
        "samples": len(selected),
        "uploadMiBPerSecondP50": rate,
        "uploadSeconds": distribution(row["uploadSeconds"] for row in selected),
    }

sum_rates = sum(tenant_rates)
fairness = (sum_rates * sum_rates) / (len(tenant_rates) * sum(rate * rate for rate in tenant_rates))
total_bytes = sum(int(row["bytes"]) for row in rows)
summary = {
    "schema": "graviton-tenant-benchmark-v1",
    "revision": rows[0]["revision"],
    "backendDescription": rows[0]["backendDescription"],
    "payloadBytes": rows[0]["bytes"],
    "payloadSha256": rows[0]["payloadSha256"],
    "tenantCount": tenant_count,
    "samplesPerTenant": samples_per_tenant,
    "samples": expected,
    "wallSeconds": elapsed_seconds,
    "aggregateUploadMiBPerSecond": (total_bytes / 1048576) / elapsed_seconds,
    "uploadSeconds": distribution(row["uploadSeconds"] for row in rows),
    "uploadMiBPerSecond": distribution(row["uploadMiBPerSecond"] for row in rows),
    "downloadSeconds": distribution(row["downloadSeconds"] for row in rows),
    "jainFairnessIndex": fairness,
    "perTenant": per_tenant,
    "verified": True,
}
(output / "samples.ndjson").write_text("".join(json.dumps(row, sort_keys=True) + "\n" for row in rows))
(output / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
print(json.dumps(summary, indent=2, sort_keys=True))
PY
