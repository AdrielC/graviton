#!/usr/bin/env python3
"""Retain bounded high-water and counter deltas from live Prometheus endpoints."""

import json
import math
import re
import signal
import sys
import time
import urllib.request

if len(sys.argv) < 3:
    raise SystemExit("usage: monitor-performance-telemetry.py <output.json> <metrics-url> [metrics-url ...]")

output_path = sys.argv[1]
urls = sys.argv[2:]
running = True
metric_re = re.compile(r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+([^\s]+)(?:\s+\d+)?$")
label_re = re.compile(r'(\w+)="((?:\\.|[^"])*)"')

def stop(_signum, _frame):
    global running
    running = False

signal.signal(signal.SIGINT, stop)
signal.signal(signal.SIGTERM, stop)

def parse(text):
    rows = []
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        match = metric_re.match(line)
        if not match:
            continue
        try:
            value = float(match.group(3))
        except ValueError:
            continue
        if not math.isfinite(value):
            continue
        labels = {key: value for key, value in label_re.findall(match.group(2) or "")}
        rows.append((match.group(1), labels, value))
    return rows

def scrape(url):
    with urllib.request.urlopen(url, timeout=5) as response:
        return parse(response.read().decode("utf-8"))

def total(rows, name, predicate=lambda _labels: True):
    values = [value for metric, labels, value in rows if metric == name and predicate(labels)]
    return sum(values) if values else None

start = time.monotonic()
samples = 0
failures = 0
first = {}
last = {}
high = {
    "heapHighWaterBytes": None,
    "postgresAwaitingHighWater": None,
    "repairBacklogHighWater": None,
}
observed = set()
current_backlog = None

while running:
    combined = []
    complete = True
    for index, url in enumerate(urls):
        try:
            rows = scrape(url)
            combined.extend((f"node-{index}", metric, labels, value) for metric, labels, value in rows)
        except Exception:
            failures += 1
            complete = False
    if complete:
        samples += 1
        flattened = [(metric, labels, value) for _, metric, labels, value in combined]
        heap = total(flattened, "jvm_memory_used_bytes", lambda labels: labels.get("area") == "heap")
        awaiting = total(flattened, "graviton_postgres_pool_awaiting_connections")
        backlog = total(flattened, "graviton_replica_under_protected_blocks")
        current_backlog = backlog
        for key, value in (
            ("heapHighWaterBytes", heap),
            ("postgresAwaitingHighWater", awaiting),
            ("repairBacklogHighWater", backlog),
        ):
            if value is not None:
                observed.add(key)
                high[key] = value if high[key] is None else max(high[key], value)

        counters = {}
        for node, metric, labels, value in combined:
            if metric in {
                "jvm_gc_collection_seconds_sum",
                "jvm_gc_collection_seconds_count",
                "graviton_s3_api_calls_total",
                "graviton_s3_retries_total",
            }:
                identity = (node, metric, tuple(sorted(labels.items())))
                counters[identity] = value
                observed.add(metric)
        for identity, value in counters.items():
            first.setdefault(identity, value)
            last[identity] = value
    if running:
        time.sleep(0.25)

def delta(metric):
    return sum(max(0.0, value - first.get(identity, 0.0)) for identity, value in last.items() if identity[1] == metric)

result = {
    "schema": "graviton-performance-telemetry-v1",
    "durationSeconds": time.monotonic() - start,
    "metricsUrls": urls,
    "samples": samples,
    "scrapeFailures": failures,
    **high,
    "gcPauseSecondsDelta": delta("jvm_gc_collection_seconds_sum"),
    "gcCollectionsDelta": delta("jvm_gc_collection_seconds_count"),
    "s3ApiCallsDelta": delta("graviton_s3_api_calls_total"),
    "s3RetriesDelta": delta("graviton_s3_retries_total"),
    "repairBacklogFinal": current_backlog if "repairBacklogHighWater" in observed else None,
    "applicability": {
        "repairBacklog": "repairBacklogHighWater" in observed,
        "s3": "graviton_s3_api_calls_total" in observed,
    },
}
with open(output_path, "w", encoding="utf-8") as output:
    json.dump(result, output, indent=2, sort_keys=True)
    output.write("\n")
