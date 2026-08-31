# Constraints and Metrics

Graviton has a small set of legacy utility primitives and a separate set of controls that the packaged server actually wires. This page keeps those categories distinct.

## Enforced server controls

- `TransferBudget` reserves named process, tenant, and backend byte footprints before a source, manifest, or block stream is demanded. Permits are scoped and released on success, failure, early termination, or interruption.
- `TenantAdmission` bounds concurrent logical operations per tenant in multi-tenant mode.
- PostgreSQL retained-byte accounting is transactionally coupled to tenant manifest publication and deletion.
- `RateLimiter` provides bounded, sharded, process-local per-principal request, upload-byte, and download-byte token buckets.
- Optional `RedisDistributedAdmission` atomically coordinates service, tenant, and backend transfer footprints across nodes.
- The same optional Redis or Valkey provider enforces authenticated HTTP and gRPC request and delivered-egress tenant ceilings. Both transports charge delivered bytes immediately before their outbound chunk or frame crosses the transport boundary.

The packaged runtime does not depend on `SpillPolicy`, `SemaphoreLimit`, or the in-memory `Quota` helper for these production contracts. Those small types remain library utilities. `SpillPolicy` currently stores a root path only; it is not a complete spill manager and must not be described as moving arbitrary uploads off heap.

## Metrics

Runtime components publish through `MetricsRegistry` and the bounded names in `MetricKeys`. The packaged registry writes native ZIO Metrics while retaining a process snapshot for `/api/stats`, the typed operator API, and the local console. `/metrics` is rendered by the Prometheus connector and includes JVM metrics.

Implemented observations include:

- blob operation outcomes, failures, duration, bytes, fresh blocks, duplicate blocks, and byte-weighted reuse;
- HTTP requests, errors, latency, and delivered egress;
- Shardcake health, assignments, observed nodes, locality decisions, failures, and reassignments;
- resumable upload creation, parts, retries, commits, staging bytes, commit duration, and cleanup duration;
- replica and erasure placement, reads, writes, reconstruction, health, repair cycles, cursor, backlog, and dead letters;
- local and distributed admission outcomes, waits, occupancy, lease loss, and traffic-quota rejection;
- PostgreSQL pool occupancy and waiters;
- S3 API calls, duration, and retries;
- backend operation outcomes, duration, transferred bytes, and maintenance cycles.

Metric labels use bounded dimensions such as cell, node, backend, operation, outcome, route, and failure stage. Tenant IDs, principals, session IDs, object names, filenames, digests, and payload values are excluded.

`graviton_duplicate_block_bytes_total / (graviton_fresh_block_bytes_total + graviton_duplicate_block_bytes_total)` is the byte-weighted share of logical ingest that found an existing CAS block. It measures avoided CAS block writes, not physical disk savings. Replication, erasure coding, allocator and object metadata, compression, and backend-native behavior must be measured separately.

Process metrics reset when the server restarts. The repository supplies Prometheus remote-write configuration and Grafana dashboards, but durable retention is provided by the configured external telemetry system. Physical-capacity measurements and RocksDB compaction metrics are not implemented.
