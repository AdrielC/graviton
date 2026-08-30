#!/usr/bin/env python3
"""Regression checks for version-aware performance telemetry requirements."""

import copy
import json
import pathlib
import subprocess
import tempfile


WORKLOAD = {
    "schema": "graviton-tenant-benchmark-v1",
    "revision": "revision",
    "payloadSha256": "0" * 64,
    "samples": 128,
    "uploadSeconds": {"p50": 1.0, "p95": 1.1, "p99": 1.2},
    "aggregateUploadMiBPerSecond": 100.0,
    "jainFairnessIndex": 1.0,
}

TELEMETRY = {
    "schema": "graviton-performance-telemetry-v1",
    "samples": 3,
    "scrapeFailures": 0,
    "heapHighWaterBytes": 1000,
    "postgresAwaitingHighWater": 0,
    "gcPauseSecondsDelta": 0.1,
    "s3ApiCallsDelta": 10,
    "s3RetriesDelta": 0,
    "repairBacklogFinal": None,
    "applicability": {"s3": True, "repairBacklog": False},
}


def run_gate(baseline_telemetry, candidate_telemetry):
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        paths = [root / name for name in ("baseline.json", "candidate.json", "baseline-telemetry.json", "candidate-telemetry.json")]
        values = [WORKLOAD, {**WORKLOAD, "revision": "candidate"}, baseline_telemetry, candidate_telemetry]
        for path, value in zip(paths, values):
            path.write_text(json.dumps(value))
        output = root / "gate.json"
        result = subprocess.run(
            [
                str(pathlib.Path(__file__).with_name("evaluate-performance-gate.py")),
                *(str(path) for path in paths),
                str(output),
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        return result.returncode, json.loads(output.read_text())


baseline_without_new_metric = copy.deepcopy(TELEMETRY)
baseline_without_new_metric["postgresAwaitingHighWater"] = None
code, result = run_gate(baseline_without_new_metric, TELEMETRY)
assert code == 0 and result["passed"], result
baseline_check = next(check for check in result["checks"] if check["name"] == "baseline exposes postgresAwaitingHighWater")
assert baseline_check["passed"] and baseline_check["applicable"] is False, baseline_check

candidate_without_required_metric = copy.deepcopy(TELEMETRY)
candidate_without_required_metric["postgresAwaitingHighWater"] = None
code, result = run_gate(TELEMETRY, candidate_without_required_metric)
assert code == 1 and not result["passed"], result
candidate_check = next(check for check in result["checks"] if check["name"] == "candidate exposes postgresAwaitingHighWater")
assert not candidate_check["passed"], candidate_check

print("performance gate regression checks passed")
