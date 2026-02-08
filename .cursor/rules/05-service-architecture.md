# Service Architecture — ZIO Layer Patterns

## Overview

Graviton follows the ZIO 2.x service pattern: traits define service interfaces, final
classes implement them, companion objects provide `ZLayer` constructors, and the server
`Main` wires everything together.

## Service Pattern

```scala
// 1. Trait (in graviton-runtime)
trait BlockStore:
  def putBlocks(plan: BlockWritePlan = BlockWritePlan()): BlockSink
  def get(key: BinaryKey.Block): ZStream[Any, Throwable, Byte]
  def exists(key: BinaryKey.Block): ZIO[Any, Throwable, Boolean]

// 2. Implementation (in graviton-runtime or backend module)
final class FsBlockStore(root: Path, prefix: String) extends BlockStore:
  override def putBlocks(...) = ...

// 3. ZLayer constructor (in companion)
object FsBlockStore:
  def layer(root: Path, prefix: String = "cas/blocks"): ULayer[BlockStore] =
    ZLayer.succeed(new FsBlockStore(root, prefix))
```

## Composition Pattern

The server composes layers using `ZLayer.make`:

```scala
ZLayer.make[BlobStore](
  PgDataSource.layerFromEnv,
  PgBlobManifestRepo.layer,
  S3BlockStore.layerFromEnv,
  CasBlobStore.layer,
)
```

**Rule**: Backend selection is config-driven (environment variables), not compile-time.

## Noop / Test Doubles

Every service trait should have a noop implementation for testing:

```scala
object MetricsRegistry:
  val noop: MetricsRegistry = new MetricsRegistry:
    override def counter(...) = ZIO.unit
    override def gauge(...)   = ZIO.unit
```

## CAS Architecture

The Content-Addressable Store is layered:

```
BlobStore (logical blob API)
  └── CasBlobStore (orchestrator)
        ├── Chunker (via FiberRef — per-fiber configurable)
        ├── BlockStore (physical block persistence)
        │     ├── FsBlockStore (filesystem)
        │     ├── S3BlockStore (S3/MinIO)
        │     └── InMemoryBlockStore (tests)
        └── BlobManifestRepo (manifest persistence)
              └── PgBlobManifestRepo (Postgres)
```

## Error Strategy by Layer

| Layer       | Error Type                | Pattern                            |
|------------|---------------------------|------------------------------------|
| Core       | `Either[String, A]`       | Pure validation, no effects        |
| Streams    | `ChunkerCore.Err` (sealed)| Domain-specific, typed             |
| Runtime    | `Throwable`               | Service boundary, catches all      |
| Server     | `Throwable`               | HTTP/gRPC boundary                 |

**Bridge rule**: Convert typed errors to `Throwable` at the runtime/server boundary:
```scala
.mapError(graviton.streams.Chunker.toThrowable)
```

## Metrics Pattern

`MetricsRegistry` is a lightweight trait (counter + gauge). Implementations:
- `MetricsRegistry.noop` — for libraries and tests
- `InMemoryMetricsRegistry` — for scraping (Prometheus text format)

Metrics are tagged with `Map[String, String]` and keyed by stable `MetricKeys.*` constants.

## Resilience

- `CircuitBreaker` — minimal open/closed/half-open state machine using `Ref.Synchronized`
- `Bulkhead` — concurrency limiter using `Semaphore`

Both are constructed with `make(...)` returning `UIO[T]` and composed as plain values.
