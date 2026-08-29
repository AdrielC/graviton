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

- `ReplicaIndex`: maps a logical blob ID to the set of locators where data lives. PostgreSQL provides a transactional implementation.
- `RangeTracker`: tracks byte spans that have been persisted, enabling resumable uploads and repair jobs.
- `SchemaStore`: an experimental bounded-byte facade. It does not yet persist a schema ID, version, fingerprint, codec, or migration, and no production service mounts it as a durable schema registry.

## Constraints and throttling

Under `graviton.runtime.constraints` you will find:

- `Quota`, `Throttle`, and `SemaphoreLimit` primitives for concurrency control and rate limiting.
- `SpillPolicy` and related value objects that describe how to offload large uploads to disk when in-memory buffering would exceed limits.

These abstractions will be composed by higher-level services (CLI ingest, HTTP gateways, background repair) once wiring is complete.

## Upload orchestration

`UploadIngestor` is the transport-neutral service for one-pass ingest preparation. HTTP, the packaged gRPC server, and Shardcake owners pass it an `UploadIntent` and a live byte stream. It performs optional declared-size validation, bounded media sniffing, keyed `ChunkerProvider` selection, scoped acquisition, attribute confirmation, and CAS storage.

The probe is an Iron-refined `Chunk[Byte]` with a 4 KiB compile-time ceiling. Provider and detector identifiers are refined, and media routing uses normalized ZIO Blocks `MediaType` keys. Detectors see only the bounded prefix; they never consume or collect the upload. Unknown formats select the registered default provider without recording a fabricated detected type.

## Metrics facade

- `MetricsRegistry` offers a uniform API for counters, gauges, and histograms.
- `MetricKeys` enumerates the well-known labels consumed by the registry (bytes ingested, backend failures, queue depth, etc.).

Backend adapters obtain a `MetricsRegistry` and emit events via module-specific metric helpers (see `PgMetrics`, `S3Metrics`). The runtime module stays vendor-neutral by restricting itself to key naming conventions.

## Reference implementations

- `InMemoryBlockStore` provides a deterministic, thread-safe `BlockStore` backed by a `Ref[Map[BinaryKey.Block, CanonicalBlock]]`. It deduplicates blocks, assembles `BlockManifest`s, and calls `BlockFramer.synthesizeBlock` for fresh blocks when the write plan uses supported framing (plain block-per-frame).
- `InMemoryBlobStore` is a **test-only** `BlobStore` (under `src/test`) that hashes whole blobs for quick contract tests — do not reference it from production `main` code.
- `Graviton.inMemory()` and `Graviton.fs(...)` are the supported ways to obtain a **`BlobStore`** in examples and applications without Postgres.

`InMemoryBlockStore` exposes a `layer` helper. For a `BlobStore` layer in tests, build from `Graviton`:

```scala
import graviton.runtime.Graviton
import graviton.runtime.stores.BlobStore
import zio.*

val blobStoreLayer: ZLayer[Any, Nothing, BlobStore] =
  ZLayer.fromZIO(Graviton.inMemory().map(_.blobStore))
```

Use filesystem + inline manifests (`Graviton.fs`) or the test `InMemoryBlobStore` when you need a different trade-off inside **test sources only**.

## Usage

1. Choose the backend layers you need (e.g., `S3Layers.live`, `PgLayers.live`).
2. Provide a `StorePolicy` and `MetricsRegistry` at startup.
3. Wire the runtime ports into the protocol servers (`graviton-http`, `graviton-grpc`) or your own services.

Many ports still have stub backends; see **[Storage backends](../runtime/backends.md)** for a truthful module-by-module status.
