#!/usr/bin/env python3
"""Compare same-runner baseline and candidate tenant workloads and telemetry."""

import json
import os
import pathlib
import sys

if len(sys.argv) != 6:
    raise SystemExit(
        "usage: evaluate-performance-gate.py <baseline-summary> <candidate-summary> "
        "<baseline-telemetry> <candidate-telemetry> <output.json>"
    )

def load(path, schema):
    value = json.loads(pathlib.Path(path).read_text())
    if value.get("schema") != schema:
        raise SystemExit(f"{path}: expected schema {schema}")
    return value

baseline = load(sys.argv[1], "graviton-tenant-benchmark-v1")
candidate = load(sys.argv[2], "graviton-tenant-benchmark-v1")
baseline_telemetry = load(sys.argv[3], "graviton-performance-telemetry-v1")
candidate_telemetry = load(sys.argv[4], "graviton-performance-telemetry-v1")

if baseline["payloadSha256"] != candidate["payloadSha256"] or baseline["samples"] != candidate["samples"]:
    raise SystemExit("baseline and candidate workloads are not comparable")

def env_float(name, default):
    return float(os.environ.get(name, default))

checks = []

def check(name, passed, baseline_value, candidate_value, limit, unit=None):
    checks.append({
        "name": name,
        "passed": bool(passed),
        "baseline": baseline_value,
        "candidate": candidate_value,
        "limit": limit,
        "unit": unit,
    })

for percentile, allowed in (("p50", 1.25), ("p95", 1.30), ("p99", 1.35)):
    old = float(baseline["uploadSeconds"][percentile])
    new = float(candidate["uploadSeconds"][percentile])
    check(f"upload latency {percentile}", new <= old * allowed, old, new, old * allowed, "seconds")

old_throughput = float(baseline["aggregateUploadMiBPerSecond"])
new_throughput = float(candidate["aggregateUploadMiBPerSecond"])
throughput_floor = old_throughput * env_float("GRAVITON_GATE_THROUGHPUT_FACTOR", 0.75)
check("aggregate upload throughput", new_throughput >= throughput_floor, old_throughput, new_throughput, throughput_floor, "MiB/s")

old_fairness = float(baseline["jainFairnessIndex"])
new_fairness = float(candidate["jainFairnessIndex"])
fairness_floor = max(env_float("GRAVITON_GATE_MIN_FAIRNESS", 0.90), old_fairness - 0.05)
check("tenant fairness", new_fairness >= fairness_floor, old_fairness, new_fairness, fairness_floor, "Jain index")

for label, telemetry in (("baseline", baseline_telemetry), ("candidate", candidate_telemetry)):
    check(f"{label} telemetry samples", telemetry["samples"] >= 3, 3, telemetry["samples"], 3, "samples")
    check(f"{label} telemetry scrape failures", telemetry["scrapeFailures"] == 0, 0, telemetry["scrapeFailures"], 0, "failures")
    check(
        f"{label} exposes heapHighWaterBytes",
        telemetry.get("heapHighWaterBytes") is not None,
        True,
        telemetry.get("heapHighWaterBytes"),
        "present",
    )
    if label == "candidate":
        check(
            f"{label} exposes postgresAwaitingHighWater",
            telemetry.get("postgresAwaitingHighWater") is not None,
            True,
            telemetry.get("postgresAwaitingHighWater"),
            "present",
        )
        check(f"{label} exposes S3 metrics", telemetry["applicability"].get("s3") is True, True, telemetry["applicability"].get("s3"), True)
    elif telemetry.get("postgresAwaitingHighWater") is None:
        checks.append({
            "name": "baseline exposes postgresAwaitingHighWater",
            "passed": True,
            "applicable": False,
            "reason": "baseline revision predates this candidate-required metric",
        })
    else:
        check(
            f"{label} exposes postgresAwaitingHighWater",
            True,
            True,
            telemetry["postgresAwaitingHighWater"],
            "present",
        )

old_heap = float(baseline_telemetry["heapHighWaterBytes"] or 0.0)
new_heap = float(candidate_telemetry["heapHighWaterBytes"] or 0.0)
heap_limit = old_heap * env_float("GRAVITON_GATE_HEAP_FACTOR", 1.35) + env_float("GRAVITON_GATE_HEAP_SLACK_BYTES", 67108864)
check("heap high-water", new_heap <= heap_limit, old_heap, new_heap, heap_limit, "bytes")

old_gc = float(baseline_telemetry["gcPauseSecondsDelta"])
new_gc = float(candidate_telemetry["gcPauseSecondsDelta"])
gc_limit = old_gc * env_float("GRAVITON_GATE_GC_FACTOR", 1.50) + env_float("GRAVITON_GATE_GC_SLACK_SECONDS", 0.25)
check("GC pause time", new_gc <= gc_limit, old_gc, new_gc, gc_limit, "seconds")

pg_limit = env_float("GRAVITON_GATE_MAX_PG_WAITERS", 4)
check(
    "PostgreSQL waiters",
    float(candidate_telemetry["postgresAwaitingHighWater"] or 0.0) <= pg_limit,
    baseline_telemetry["postgresAwaitingHighWater"],
    candidate_telemetry["postgresAwaitingHighWater"],
    pg_limit,
    "connections",
)

s3_calls = float(candidate_telemetry["s3ApiCallsDelta"])
s3_retries = float(candidate_telemetry["s3RetriesDelta"])
retry_rate = s3_retries / max(1.0, s3_calls)
retry_limit = env_float("GRAVITON_GATE_MAX_S3_RETRY_RATE", 0.05)
check("S3 retry rate", retry_rate <= retry_limit, None, retry_rate, retry_limit, "retries/call")

if candidate_telemetry["applicability"].get("repairBacklog"):
    check(
        "repair backlog drains",
        float(candidate_telemetry["repairBacklogFinal"] or 0.0) == 0.0,
        baseline_telemetry.get("repairBacklogFinal"),
        candidate_telemetry.get("repairBacklogFinal"),
        0,
        "blocks",
    )
else:
    checks.append({"name": "repair backlog", "passed": True, "applicable": False, "reason": "cohort has no convergent replica service"})

passed = all(item["passed"] for item in checks)
result = {
    "schema": "graviton-performance-gate-v1",
    "passed": passed,
    "baselineRevision": baseline["revision"],
    "candidateRevision": candidate["revision"],
    "checks": checks,
}
pathlib.Path(sys.argv[5]).write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
print(json.dumps(result, indent=2, sort_keys=True))
raise SystemExit(0 if passed else 1)
