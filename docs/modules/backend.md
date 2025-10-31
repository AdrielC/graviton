# Backend Adapters

The backend modules provide concrete implementations for the storage ports that live in `graviton-runtime`. Every adapter wires the shared traits (`BlobStore`, `MutableObjectStore`, `ReplicaIndex`, etc.) to a particular persistence technology so that the runtime can stay agnostic of vendor-specific concerns.

| Module | Purpose | Key interfaces | Status |
| --- | --- | --- | --- |
| `backend/graviton-pg` | Relational storage for blobs, ranges, and metadata | `ImmutableObjectStore`, `MutableObjectStore`, `KeyValueStore`, `RangeTracker`, `ReplicaIndex` | Scaffolding in place; methods currently stubbed with TODOs |
| `backend/graviton-s3` | Object storage against S3-compatible APIs | `BlobStore`, `ImmutableObjectStore`, `MutableObjectStore` | API skeletons implemented; upload/download logic still unimplemented |
| `backend/graviton-rocks` | Embedded RocksDB key-value store for local/dev workloads | `KeyValueStore` | Traits wired but persistence calls are placeholders |

## PostgreSQL adapter (`graviton-pg`)

- **Scope**: Implements the full suite of runtime ports using PostgreSQL. The Flyway-style migration `migrations/V1__init.sql` establishes the initial `objects` and `kv` tables.
- **Implemented layers**: `PgLayers.live` currently provides a `PgMutableObjectStore`. Additional layers for indexes and metrics will follow as implementations mature.
- **Operations**: The concrete classes (`PgImmutableObjectStore`, `PgMutableObjectStore`, `PgKeyValueStore`, `PgRangeTracker`, `PgReplicaIndex`) currently return placeholder values (`ZIO.unit`, empty streams, or `None`). They exist to validate wiring and to document the intended surface.
- **Next steps**:
  - Back primary blob content with large-object or bytea streaming APIs.
  - Persist deduplication metadata in the `kv` table with transactional semantics.
  - Implement range tracking using window functions to support resumable uploads.
  - Surface metrics through `PgMetrics` instead of no-op counters.

## S3 adapter (`graviton-s3`)

- **Scope**: Targets AWS S3 (and S3-compatible endpoints) for blob durability. The module exposes `S3MutableObjectStore`, `S3ImmutableObjectStore`, and `S3BlobStore` so the runtime can delegate object lifecycle operations.
- **Metrics**: `S3Metrics` already records counters against the shared `MetricsRegistry` (e.g., `MetricKeys.BytesIngested`). Add gauges for latency and failure classifications once the happy-path code lands.
- **Current state**: Upload/download/delete/stats paths still call `ZSink.fail` or return empty streams. Replace these stubs with the AWS SDK v2 client, multipart upload orchestration, and retry policies.
- **Next steps**:
  - Inject configuration (bucket name, region, credentials) via ZIO layers.
  - Support server-side encryption and optional customer-managed keys.
  - Emit structured events so the replication layer can observe backend results.

## RocksDB adapter (`graviton-rocks`)

- **Scope**: Provides an embedded key-value store for development, edge deployments, and caching layers.
- **Implementation**: `RocksKeyValueStore` currently stubs `put/get/delete`. Integrate with the RocksDB Java bindings, enable column families for different namespaces, and expose compaction metrics.
- **Operational notes**: When implemented, the Rocks adapter will be well-suited for local demos or as a metadata sidecar when the primary blob store lives elsewhere.

## Integration checklist

To promote a backend from scaffold to production-ready, complete the following sequence:

1. Replace placeholder implementations with real drivers and wire configuration via `ZLayer`.
2. Cover error handling with retry policies and idempotent semantics for each port.
3. Extend metrics classes so they increment counters and histograms in `MetricsRegistry`.
4. Add integration tests under `modules/core/src/test` or backend-specific suites once TestContainers support lands.
5. Update the documentation with configuration snippets and operational guidance (credentials, IAM policies, replication topology).
