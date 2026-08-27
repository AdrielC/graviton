# Constraints and Metrics

Graviton enforces ingest limits and exposes observability data through the runtime module.

## Constraints

- **Spill policy** – keeps large uploads off-heap by writing intermediary bytes to disk. Policies track the lifetime of spill directories and cleaning semantics.
- **Semaphore limits** – coordinate concurrency per tenant and per upload via `zio.Semaphore`.
- **Throttles** – token bucket implementation to enforce byte-per-second limits across actors.
- **Quotas** – track aggregate usage per tenant; the initial implementation uses counters and optimistic updates.

## Metrics

Runtime components register metrics through `MetricsRegistry` and publish canonical keys defined in `MetricKeys`. The server exposes process-local ingest counters through `/api/stats` and Prometheus text through `/metrics`.

The implemented counters cover successful ingests, ingested bytes, fresh blocks, duplicate blocks, HTTP outcomes, and Shardcake upload-locality decisions and failures. The Shardcake adapter also publishes bounded hot-state entry count. Locality labels are restricted to route and failure stage; tenant and session IDs are intentionally excluded to prevent unbounded cardinality.

Process metrics reset when the server restarts. Backend latency histograms, durable aggregation, RocksDB compaction gauges, and S3 health measurements remain roadmap work.
