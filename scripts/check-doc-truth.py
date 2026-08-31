#!/usr/bin/env python3
"""Fail when audited documentation evidence drifts away from the repository."""

from __future__ import annotations

import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / "docs/status/implementation-evidence.json"
ALLOWED_STATUSES = {
    "released-v0.7.0",
    "optional-released-v0.7.0",
    "main-only",
    "optional-main-only",
    "target-required",
    "not-shipped",
}
CURRENT_DOCS = [
    *ROOT.glob("*.md"),
    *(ROOT / "docs").rglob("*.md"),
    *(ROOT / "deploy").rglob("*.md"),
    *(ROOT / "modules").rglob("README.md"),
]
EXCLUDED_DOC_PARTS = {"node_modules", "target", "dist", ".vitepress", "public"}
CURRENT_DOCS = [
    path
    for path in CURRENT_DOCS
    if "docs/logs" not in path.as_posix()
    and not any(part in EXCLUDED_DOC_PARTS for part in path.parts)
]
FORBIDDEN_CURRENT_TEXT = {
    "scripts/qualify-rolling-upgrade.sh": "the script was replaced by same-version node replacement qualification",
    "MetricKeys.TikaParsed": "no Tika metrics or module ship",
    "MetricKeys.TikaRejected": "no Tika metrics or module ship",
    "MetricKeys.TikaLatency": "no Tika metrics or module ship",
    "Wire a runnable authenticated gRPC server": "authenticated gRPC is already implemented",
    "## 0.6 release boundary": "the latest release is v0.7.0",
    "Configure Sonatype credentials and PGP signing secrets before claiming Maven Central availability": "v0.7.0 is published on Maven Central",
    "replica placement that uses it across the runtime is **planned**": "rendezvous replica placement is wired",
    "confirmed attributes are returned to the caller but are not yet durably stored": "BlobMetadataV1 persists bounded semantic metadata",
    "runs at the '''same speed as hand-written imperative code'''": "performance requires measurement",
    "summary contains **all** named fields from **all** stages, accessible by name": "mixed-field Kyo Record access is not reliable on Scala 3.8",
}


def fail(message: str) -> None:
    raise SystemExit(f"documentation truth check failed: {message}")


def read(path: Path) -> str:
    if not path.is_file():
        fail(f"missing evidence file {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def verify_locator(claim_id: str, locator: dict[str, str]) -> None:
    path = ROOT / locator["path"]
    text = read(path)
    if "contains" in locator and locator["contains"] not in text:
        fail(f"{claim_id}: {locator['path']} no longer contains {locator['contains']!r}")
    if "notContains" in locator and locator["notContains"] in text:
        fail(f"{claim_id}: {locator['path']} unexpectedly contains {locator['notContains']!r}")


def verify_evidence() -> None:
    payload = json.loads(read(EVIDENCE))
    if payload.get("schemaVersion") != 1:
        fail("implementation evidence schemaVersion must be 1")
    claims = payload.get("claims")
    if not isinstance(claims, list) or not claims:
        fail("implementation evidence must contain claims")
    seen: set[str] = set()
    for claim in claims:
        claim_id = claim.get("id")
        if not isinstance(claim_id, str) or not claim_id or claim_id in seen:
            fail(f"invalid or duplicate claim id {claim_id!r}")
        seen.add(claim_id)
        status = claim.get("status")
        if status not in ALLOWED_STATUSES:
            fail(f"{claim_id}: unsupported status {status!r}")
        docs = claim.get("documentation")
        if not isinstance(docs, list) or not docs:
            fail(f"{claim_id}: at least one documentation path is required")
        for path in docs:
            read(ROOT / path)
        implementation = claim.get("implementation")
        tests = claim.get("tests")
        if not isinstance(implementation, list) or not implementation:
            fail(f"{claim_id}: implementation or absence evidence is required")
        if status != "not-shipped" and (not isinstance(tests, list) or not tests):
            fail(f"{claim_id}: executable proof is required for a shipped claim")
        for locator in [*implementation, *(tests or [])]:
            verify_locator(claim_id, locator)


def verify_current_claims() -> None:
    for path in CURRENT_DOCS:
        text = read(path)
        for forbidden, reason in FORBIDDEN_CURRENT_TEXT.items():
            if forbidden in text:
                fail(f"{path.relative_to(ROOT)} contains stale claim {forbidden!r}: {reason}")

        for script in sorted(set(re.findall(r"(?:\./)?(scripts/[A-Za-z0-9._/-]+)", text))):
            candidate = script.rstrip("`.,;:)")
            if not (ROOT / candidate).is_file():
                fail(f"{path.relative_to(ROOT)} references missing command {candidate}")

    metric_keys = read(ROOT / "modules/graviton-runtime/src/main/scala/graviton/runtime/metrics/MetricKeys.scala")
    for path in CURRENT_DOCS:
        for key in sorted(set(re.findall(r"MetricKeys\.([A-Za-z0-9_]+)", read(path)))):
            if re.search(rf"\bval\s+{re.escape(key)}\b", metric_keys) is None:
                fail(f"{path.relative_to(ROOT)} references nonexistent MetricKeys.{key}")


if __name__ == "__main__":
    verify_evidence()
    verify_current_claims()
    print("documentation evidence and current claims are consistent")
