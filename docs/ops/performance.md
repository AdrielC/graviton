# Performance Measurement

Graviton includes real HTTP measurement and soak tools. The repository does not publish a universal throughput or latency claim because backend, hardware, network, payload distribution, concurrency, and duplication materially change results.

## One verified measurement

Start a server, then run:

```bash
GRAVITON_BENCHMARK_BACKEND_DESCRIPTION='filesystem, APFS, local process, 1 MiB chunks' \
  ./scripts/benchmark-http.sh \
  http://localhost:8081 \
  ./representative-payload.bin \
  > benchmark.json
```

For a secured server, export `GRAVITON_BEARER_TOKEN`. The token is used as an HTTP header and is not written to the result.

The script performs a real upload, byte-for-byte download comparison, and server-side verification. Its JSON includes:

- schema version and UTC timestamp
- exact Git revision and dirty-worktree state
- operating system, Python, and Java version
- operator-supplied backend description
- payload byte count and SHA-256
- upload and download duration and MiB/s
- verification duration
- returned blob content ID

This is one measured sample, not a distribution.

## Soak loop

```bash
./scripts/soak-http.sh http://localhost:8081 1000 1048576 \
  > soak.json
```

Every iteration uploads real bytes and compares the downloaded stream. The command fails if any iteration fails and emits machine-readable iteration, payload, duration, and failure counts. It intentionally uses a repeated payload to exercise duplicate-block behavior. Use a representative corpus for broader qualification.

## Required benchmark record

Before publishing a result, retain:

- raw JSON and command line
- exact commit and clean/dirty state
- server and client host topology
- CPU, memory, operating system, JDK, filesystem, object store, and database versions
- Graviton backend, chunk size, security mode, and non-default limits
- dataset source, checksum, size distribution, and duplication ratio
- warm-up, iterations, concurrency, ordering, and cache state
- p50, p95, and p99 from retained raw samples
- error and retry counts

Never infer throughput from block size or a single process counter. Never compare two revisions using different data, hosts, caches, or backend settings without labeling the difference.

## Comparing revisions

1. Use two clean clones with the same JDK.
2. Use immutable payloads and record checksums.
3. Use fresh storage roots or equivalent backend namespaces.
4. Warm both revisions with the same unmeasured workload.
5. Randomize run order.
6. Capture individual samples rather than only an average.
7. Test cold and warm reads separately.
8. Treat changes smaller than run-to-run variation as inconclusive.

## Current observability boundary

`/api/stats` and `/metrics` expose process-local ingest, deduplication, HTTP request, error, and latency observations. They reset on restart and do not establish durable capacity or a service-level objective. Backend-specific histograms and retained dashboards remain deployment work.
