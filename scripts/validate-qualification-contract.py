#!/usr/bin/env python3
"""Validate the honest, machine-readable production qualification contract."""

from __future__ import annotations

import json
import pathlib
import sys


ROOT = pathlib.Path(__file__).resolve().parents[1]
MATRIX = ROOT / "deploy/qualification-v1/matrix.json"
REQUIRED_GATES = {
    "streaming-laws",
    "crash-consistency",
    "tenant-noisy-neighbor",
    "manager-node-replacement",
    "resumable-replacement",
    "object-manifest-outage",
    "valkey-atomic-contracts",
    "backup-isolated-restore",
    "gc-maintenance-lock",
    "sustained-soak",
    "heap-direct-memory-latency-retry-dedup",
    "valkey-failover-partition",
    "rds-failover",
    "zone-impairment",
    "s3-throttling-timeouts",
    "provider-backup-restore",
}
EXPECTED_STATUS = {
    "repository": "repository-verified",
    "scheduled": "scheduled",
    "target": "target-required",
}


def fail(message: str) -> None:
    raise SystemExit(f"qualification contract invalid: {message}")


def main() -> None:
    document = json.loads(MATRIX.read_text(encoding="utf-8"))
    if document.get("schema") != "graviton-production-qualification-matrix-v1":
        fail("unknown schema")
    if int(document.get("minimumSoakMinutes", 0)) < 60:
        fail("minimum soak must be at least 60 minutes")

    gates = document.get("gates")
    if not isinstance(gates, list):
        fail("gates must be an array")
    ids = [gate.get("id") for gate in gates if isinstance(gate, dict)]
    if len(ids) != len(set(ids)):
        fail("gate identifiers must be unique")
    missing = sorted(REQUIRED_GATES - set(ids))
    extra = sorted(set(ids) - REQUIRED_GATES)
    if missing or extra:
        fail(f"gate set mismatch; missing={missing}, extra={extra}")

    counts = {key: 0 for key in EXPECTED_STATUS}
    for gate in gates:
        if not isinstance(gate, dict):
            fail("each gate must be an object")
        gate_class = gate.get("class")
        if gate_class not in EXPECTED_STATUS:
            fail(f"gate {gate.get('id')} has unknown class")
        if gate.get("status") != EXPECTED_STATUS[gate_class]:
            fail(f"gate {gate.get('id')} overstates or misclassifies its status")
        evidence = gate.get("evidence")
        if not isinstance(evidence, str) or not evidence:
            fail(f"gate {gate.get('id')} has no evidence locator")
        for locator in evidence.split():
            if locator.startswith(("scripts/", "deploy/", "modules/", "docs/")) and not (ROOT / locator).exists():
                fail(f"gate {gate.get('id')} references missing evidence {locator}")
        counts[gate_class] += 1

    output = {
        "schema": "graviton-production-qualification-contract-validation-v1",
        "status": "passed",
        "minimumSoakMinutes": document["minimumSoakMinutes"],
        "gates": len(gates),
        "classes": counts,
    }
    print(json.dumps(output, sort_keys=True, separators=(",", ":")))


if __name__ == "__main__":
    main()
