#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 5 ]]; then
  echo "usage: $0 <base-url> <payload-file> [samples=10] [concurrency=1] [output-directory=benchmark-results]" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
base_url="${1%/}"
payload="$2"
samples="${3:-10}"
concurrency="${4:-1}"
output_dir="${5:-benchmark-results}"
warmups="${GRAVITON_BENCHMARK_WARMUPS:-2}"

[[ -f "$payload" ]] || { echo "payload not found: $payload" >&2; exit 1; }
[[ "$samples" =~ ^[1-9][0-9]*$ ]] || { echo "samples must be positive" >&2; exit 2; }
[[ "$concurrency" =~ ^[1-9][0-9]*$ ]] || { echo "concurrency must be positive" >&2; exit 2; }
[[ "$warmups" =~ ^[0-9]+$ ]] || { echo "GRAVITON_BENCHMARK_WARMUPS must be zero or positive" >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 2; }

mkdir -p "$output_dir/raw"
output_dir="$(cd "$output_dir" && pwd)"

for ((iteration = 1; iteration <= warmups; iteration++)); do
  "$repo_root/scripts/benchmark-http.sh" "$base_url" "$payload" >/dev/null
done

run_sample() {
  local sample="$1"
  "$repo_root/scripts/benchmark-http.sh" "$base_url" "$payload" > "$output_dir/raw/sample-$(printf '%04d' "$sample").json"
}

export -f run_sample
export repo_root base_url payload output_dir

declare -a pids
next_to_wait=1
active=0
failures=0
for ((sample = 1; sample <= samples; sample++)); do
  run_sample "$sample" &
  pids[$sample]=$!
  active=$((active + 1))
  if ((active >= concurrency)); then
    if ! wait "${pids[$next_to_wait]}"; then
      failures=$((failures + 1))
    fi
    next_to_wait=$((next_to_wait + 1))
    active=$((active - 1))
  fi
done
while ((active > 0)); do
  if ! wait "${pids[$next_to_wait]}"; then
    failures=$((failures + 1))
  fi
  next_to_wait=$((next_to_wait + 1))
  active=$((active - 1))
done

if ((failures > 0)); then
  echo "$failures benchmark sample(s) failed" >&2
  exit 1
fi

python3 - "$output_dir" "$samples" "$concurrency" "$warmups" <<'PY'
import json
import math
import pathlib
import statistics
import sys

output = pathlib.Path(sys.argv[1])
expected = int(sys.argv[2])
concurrency = int(sys.argv[3])
warmups = int(sys.argv[4])
paths = sorted((output / "raw").glob("sample-*.json"))
if len(paths) != expected:
    raise SystemExit(f"expected {expected} raw samples, found {len(paths)}")

rows = [json.loads(path.read_text()) for path in paths]
for row in rows:
    if row.get("schema") != "graviton-benchmark-v1" or row.get("verified") is not True:
        raise SystemExit("invalid or unverified benchmark sample")

def percentile(values, probability):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(probability * len(ordered)) - 1)]

def distribution(field):
    values = [float(row[field]) for row in rows]
    return {
        "min": min(values),
        "mean": statistics.fmean(values),
        "p50": percentile(values, 0.50),
        "p95": percentile(values, 0.95),
        "p99": percentile(values, 0.99),
        "max": max(values),
    }

first = rows[0]
summary = {
    "schema": "graviton-benchmark-suite-v1",
    "revision": first["revision"],
    "repositoryDirty": first["repositoryDirty"],
    "backendDescription": first["backendDescription"],
    "payloadBytes": first["bytes"],
    "payloadSha256": first["payloadSha256"],
    "samples": expected,
    "warmups": warmups,
    "concurrency": concurrency,
    "freshBlocks": sum(int(row["freshBlocks"]) for row in rows),
    "duplicateBlocks": sum(int(row["duplicateBlocks"]) for row in rows),
    "uploadSeconds": distribution("uploadSeconds"),
    "uploadMiBPerSecond": distribution("uploadMiBPerSecond"),
    "downloadSeconds": distribution("downloadSeconds"),
    "downloadMiBPerSecond": distribution("downloadMiBPerSecond"),
    "verifySeconds": distribution("verifySeconds"),
    "serverIngestSeconds": distribution("serverIngestSeconds"),
}

(output / "samples.ndjson").write_text("".join(json.dumps(row, sort_keys=True) + "\n" for row in rows))
(output / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
print(json.dumps(summary, indent=2, sort_keys=True))
PY
