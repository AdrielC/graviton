# Constraints and Metrics

Graviton enforces ingest limits and exposes observability data through the runtime module.

## Constraints

- **Spill policy** – keeps large uploads off-heap by writing intermediary bytes to disk. Policies track the lifetime of spill directories and cleaning semantics.
- **Semaphore limits** – coordinate concurrency per tenant and per upload via `zio.Semaphore`.
- **Throttles** – token bucket implementation to enforce byte-per-second limits across actors.
- **Quotas** – track aggregate usage per tenant; the initial implementation uses counters and optimistic updates.

## Metrics

All components register metrics through `MetricsRegistry` and publish canonical keys defined in `MetricKeys`. The server exposes `/metrics` for Prometheus scrapes while backends contribute driver-specific gauges (e.g., RocksDB compaction stats or S3 request latency histograms).
