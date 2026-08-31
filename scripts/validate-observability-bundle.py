#!/usr/bin/env python3
"""Fail closed on structural drift in the portable observability bundle."""

from __future__ import annotations

import json
import pathlib
import re
import sys


ROOT = pathlib.Path(__file__).resolve().parents[1]
BUNDLE = ROOT / "deploy" / "observability-v1"


def load_json(name: str) -> object:
    with (BUNDLE / name).open("r", encoding="utf-8") as handle:
        return json.load(handle)


def fail(message: str) -> None:
    raise SystemExit(f"observability bundle invalid: {message}")


def main() -> None:
    slo = load_json("slo.json")
    schema = load_json("slo.schema.json")
    routing = load_json("alert-routing.json")
    dashboard = load_json("grafana/dashboards/graviton-production.json")

    if not isinstance(slo, dict) or slo.get("schema") != "graviton-production-slo-v1":
        fail("slo.json has the wrong schema identifier")
    objectives = slo.get("objectives")
    if not isinstance(objectives, list) or len(objectives) < 3:
        fail("slo.json must declare availability, latency, and durability objectives")
    objective_ids = {entry.get("id") for entry in objectives if isinstance(entry, dict)}
    if objective_ids != {"http-availability", "http-latency", "durability-convergence"}:
        fail(f"unexpected objective set: {sorted(str(value) for value in objective_ids)}")
    if not isinstance(schema, dict) or schema.get("$id") != "https://graviton.dev/schema/production-slo-v1.json":
        fail("slo.schema.json has the wrong schema identifier")
    if not isinstance(routing, dict) or routing.get("schema") != "graviton-alert-routing-v1":
        fail("alert-routing.json has the wrong schema identifier")

    panels = dashboard.get("panels") if isinstance(dashboard, dict) else None
    if not isinstance(panels, list) or len(panels) < 12:
        fail("production dashboard must retain at least twelve operational panels")

    recording = (BUNDLE / "recording-rules.yml").read_text(encoding="utf-8")
    alerts = (BUNDLE / "alerts.yml").read_text(encoding="utf-8")
    prometheus = (BUNDLE / "prometheus.yml").read_text(encoding="utf-8")
    required_records = {entry["indicator"] for entry in objectives if isinstance(entry, dict)}
    missing_records = sorted(value for value in required_records if value not in recording)
    if missing_records:
        fail(f"recording rules do not define SLO indicators: {missing_records}")
    for required in ("severity: page", "severity: ticket", "runbook:"):
        if required not in alerts:
            fail(f"alerts.yml is missing '{required}'")
    for required in ("recording-rules.yml", "alerts.yml", "remote_write:", "metrics_path: /metrics"):
        if required not in prometheus:
            fail(f"prometheus.yml is missing '{required}'")

    forbidden = routing.get("privacy", {}).get("forbiddenLabels", [])
    rule_labels = re.findall(r"(?:by|without)\s*\(([^)]*)\)", recording + "\n" + alerts)
    observed = {label.strip() for group in rule_labels for label in group.split(",")}
    leaked = sorted(set(forbidden) & observed)
    if leaked:
        fail(f"forbidden high-cardinality labels appear in rules: {leaked}")

    print(json.dumps({
        "schema": "graviton-observability-validation-v1",
        "status": "passed",
        "objectives": sorted(objective_ids),
        "panels": len(panels),
    }, separators=(",", ":")))


if __name__ == "__main__":
    main()
