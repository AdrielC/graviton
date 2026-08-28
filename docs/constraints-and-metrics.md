# Constraints and Metrics

Graviton enforces ingest limits and exposes observability data through the runtime module.

## Constraints

- **Spill policy** – keeps large uploads off-heap by writing intermediary bytes to disk. Policies track the lifetime of spill directories and cleaning semantics.
- **Semaphore limits** – coordinate concurrency per tenant and per upload via `zio.Semaphore`.
- **Throttles** – token bucket implementation to enforce byte-per-second limits across actors.
- **Quotas** – track aggregate usage per tenant; the initial implementation uses counters and optimistic updates.

## Metrics

Runtime components register metrics through `MetricsRegistry` and publish canonical keys defined in `MetricKeys`. The server exposes process-local ingest counters through `/api/stats` and Prometheus text through `/metrics`.

The implemented counters cover successful ingests, ingested bytes, fresh and duplicate blocks, fresh and duplicate block bytes, HTTP outcomes, and Shardcake upload-locality decisions and failures. The Shardcake adapter also publishes bounded hot-state entry count. Locality labels are restricted to route and failure stage; tenant and session IDs are intentionally excluded to prevent unbounded cardinality.

`graviton_duplicate_block_bytes_total / (graviton_fresh_block_bytes_total + graviton_duplicate_block_bytes_total)` is the byte-weighted share of logical ingest that found an existing CAS block. `/api/stats` exposes this as `deduplicationRatio`. Per-file console percentages are explicitly labeled as block reuse because the catalog currently persists block counts, not byte-weighted history.

This ratio measures Graviton writes avoided at the CAS boundary. It is not physical disk savings. Replication, erasure coding, compression, object-store metadata, allocator overhead, and backend-native deduplication all affect physical utilization. Measure those at the storage backend as a separate capacity signal.

Process metrics reset when the server restarts. Backend latency histograms, durable aggregation, physical-capacity measurements, RocksDB compaction gauges, and S3-compatible backend health measurements remain roadmap work.
