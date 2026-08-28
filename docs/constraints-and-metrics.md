# Constraints and Metrics

Graviton enforces ingest limits and exposes observability data through the runtime module.

## Constraints

- **Spill policy** – keeps large uploads off-heap by writing intermediary bytes to disk. Policies track the lifetime of spill directories and cleaning semantics.
- **Semaphore limits** – coordinate concurrency per tenant and per upload via `zio.Semaphore`.
- **Throttles** – token bucket implementation to enforce byte-per-second limits across actors.
- **Quotas** – track aggregate usage per tenant; the initial implementation uses counters and optimistic updates.

## Metrics

Runtime components register metrics through `MetricsRegistry` and publish canonical keys defined in `MetricKeys`. The production registry writes to native ZIO Metrics while retaining a small process snapshot for `/api/stats` and the local Runtime console. `/metrics` is rendered by the current `zio-metrics-connectors-prometheus` publisher and includes ZIO's JVM metrics.

The implemented counters cover successful ingests, ingested bytes, fresh blocks, duplicate blocks, HTTP outcomes, and Shardcake upload-locality decisions and failures. HTTP and ingest duration are histograms. The Shardcake adapter also records health outcomes and duration plus readiness, assignment, observed-node, and bounded hot-state gauges. Metric labels are restricted to bounded dimensions such as operation, route, health status, and failure stage; tenant, session, and node IDs are intentionally excluded to prevent unbounded cardinality.

Process metrics reset when the server restarts. Durable aggregation, backend-specific latency histograms, RocksDB compaction gauges, and S3 request-level health measurements remain deployment work.
