# Performance Measurement

Graviton does not publish a throughput or latency claim yet. The repository does not contain a controlled benchmark harness, so hardware-independent targets would be misleading.

This page defines how to collect useful measurements without turning a local timing into a product claim.

## What to record

Every result should include:

- the exact commit SHA and whether the worktree was clean
- JDK, operating system, CPU, memory, and filesystem
- the selected block and manifest backends
- all non-default Graviton configuration
- dataset size, file-size distribution, and expected duplication
- warm-up policy, measured iterations, and concurrency
- whether clients and storage ran on the same host
- raw timing output and the script used to produce it

Report throughput from measured bytes and elapsed time. Report latency as a distribution, including at least p50, p95, and p99. Do not infer either value from the Capacity Lab or Pipeline Explorer. Those interfaces are deterministic browser models, not benchmark tools.

## Reproducible local smoke measurement

Start the default filesystem server:

```bash
./sbt 'server/run'
```

In another shell, create a fixture and time one upload:

```bash
fixture=$(mktemp)
headers=$(mktemp)
response=$(mktemp)

dd if=/dev/zero of="$fixture" bs=1048576 count=128

/usr/bin/time -p \
  curl --fail --silent --show-error \
    --dump-header "$headers" \
    --output "$response" \
    --data-binary @"$fixture" \
    http://localhost:8081/api/blobs

cat "$response"
curl --fail --silent --show-error http://localhost:8081/api/stats
```

This is a smoke measurement, not a benchmark. A single zero-filled file strongly favors deduplication and `/usr/bin/time` combines client, HTTP, runtime, and local storage costs.

## Comparing changes

For a useful before-and-after comparison:

1. Use two clean clones built with the same JDK.
2. Use a fresh storage root for every measured run.
3. Generate one immutable fixture set and record its checksum.
4. Warm up each build with the same unmeasured workload.
5. Randomize the order of measured runs.
6. Capture raw samples instead of reporting only an average.
7. Repeat retrieval tests with both cold and warm filesystem caches, and label them.
8. Treat a change as meaningful only when it is larger than run-to-run variation.

## Current observability boundary

The server exposes process-local ingest counters at `/api/stats` and Prometheus text at `/metrics`. Counters restart with the process. They prove what the current process observed, but they do not measure durable capacity, request latency, or backend health.

The runtime records successful ingests, ingested bytes, fresh blocks, and duplicate blocks. Backend-specific latency histograms and RocksDB health gauges are not implemented.

## Before publishing results

The release backlog requires a benchmark harness with fixed fixtures, warm-up, sampling, machine-readable output, and documented environments. Until that exists, keep performance data attached to its command and environment rather than presenting it as a Graviton guarantee.
