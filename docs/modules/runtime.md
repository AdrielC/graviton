# Runtime Module

The `graviton-runtime` project bridges the pure data model from `graviton-core` with effectful infrastructure. It defines the service ports, policies, and coordination utilities that every deployment must satisfy.

## Storage ports

All storage backends implement the following traits under `graviton.runtime.stores`:

- `BlobStore`: streaming CRUD operations for deduplicated blobs (`put`, `get`, `stat`, `delete`).
- `BlockStore`: lower-level content-addressed block access for chunk-oriented ingestion.
- `ImmutableObjectStore` / `MutableObjectStore`: abstractions for manifests and binary assets that may or may not be mutated after ingest.
- `KeyValueStore`: generic metadata persistence for manifests, replication plans, and coarse-grained indexes.

The ports are intentionally minimal—backends compose them to express capabilities (e.g., S3 supplies immutable + blob, PostgreSQL implements every port).

## Policies and layout

- `BlobLayout` and `StorePolicy` describe how manifests and chunks are stored (single object vs framed) and how large each part should be.
- `ReplicationMode` captures sync/async strategies; policies flow into replication planners once they are implemented in the runtime.

## Indexes and range tracking

- `ReplicaIndex`: maps a logical blob ID to the set of locators where data lives. Backends such as PostgreSQL will provide durable implementations.
- `RangeTracker`: tracks byte spans that have been persisted, enabling resumable uploads and repair jobs.
- `SchemaStore`: placeholder for schema evolution metadata, ensuring manifests remain forward-compatible.

## Constraints and throttling

Under `graviton.runtime.constraints` you will find:

- `Quota`, `Throttle`, and `SemaphoreLimit` primitives for concurrency control and rate limiting.
- `SpillPolicy` and related value objects that describe how to offload large uploads to disk when in-memory buffering would exceed limits.

These abstractions will be composed by higher-level services (CLI ingest, HTTP gateways, background repair) once wiring is complete.

## Metrics facade

- `MetricsRegistry` offers a uniform API for counters, gauges, and histograms.
- `MetricKeys` enumerates the well-known labels consumed by the registry (bytes ingested, backend failures, queue depth, etc.).

Backend adapters obtain a `MetricsRegistry` and emit events via module-specific metric helpers (see `PgMetrics`, `S3Metrics`). The runtime module stays vendor-neutral by restricting itself to key naming conventions.

## Reference implementations

- `InMemoryBlockStore` provides a deterministic, thread-safe `BlockStore` backed by a `Ref[Map[BinaryKey.Block, CanonicalBlock]]`. It deduplicates blocks, assembles `BlockManifest`s, and produces frame metadata via `BlockFramer` so higher layers can exercise the full ingest flow without wiring external storage.
- `InMemoryBlobStore` offers a complementary `BlobStore` that hashes payloads (SHA-256), confirms size/chunk-count attributes, records `BlobStat` data, and returns `BlobWriteResult`s with a `memory://` locator scheme.

Both stores live under `graviton.runtime.stores` and expose `make` helpers plus `ZLayer`s for quick wiring:

```scala
import graviton.runtime.stores.{InMemoryBlockStore, InMemoryBlobStore}
import zio.*

val blockStoreLayer: ULayer[BlockStore] = InMemoryBlockStore.layer
val blobStoreLayer:  ULayer[BlobStore]  = InMemoryBlobStore.layer
```

Because they only depend on `ZIO` primitives, these stores are ideal for unit tests, integration tests, and CLI prototypes that should not touch S3/PostgreSQL yet.

## Usage

1. Choose the backend layers you need (e.g., `S3Layers.live`, `PgLayers.live`).
2. Provide a `StorePolicy` and `MetricsRegistry` at startup.
3. Wire the runtime ports into the protocol servers (`graviton-http`, `graviton-grpc`) or your own services.

The implementations currently focus on type signatures and domain modelling. Filling in concrete behaviors is tracked in the v0.1.0 milestone.
