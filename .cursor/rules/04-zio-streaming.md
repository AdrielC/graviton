# ZIO Streaming — Patterns & Standards

## Overview

Graviton's streaming architecture uses ZIO Streams (ZStream, ZPipeline, ZSink, ZChannel)
as the backbone for binary ingest, block production, manifest building, and blob retrieval.
The streaming pipeline is **pull-based** with implicit chunking — ZIO handles backpressure
and resource safety automatically.

## Core Abstractions Mapping

| ZIO Concept  | Graviton Usage                                                         |
|-------------|------------------------------------------------------------------------|
| `ZStream`   | Byte sources (file/HTTP/gRPC), block ref streams from DB               |
| `ZPipeline` | Chunker, hashing pass-through, ingest programs, scodec decoders        |
| `ZSink`     | `BlockStore.putBlocks()`, `BlobStore.put()`, `Hasher.sink()`           |
| `ZChannel`  | Low-level Chunker implementation, Scan `toChannel`, scodec decoder     |
| `Queue`     | Bounded handoff between ingest and block-persist fibers                |
| `Take`      | Stream element + error + end signaling through queues                  |

## Pattern: Chunker as ZPipeline

The `Chunker` trait produces a `ZPipeline[Any, Err, Byte, Block]`. This is the canonical
way to transform a byte stream into bounded content-addressed blocks:

```scala
trait Chunker:
  def name: String
  def pipeline: ZPipeline[Any, Chunker.Err, Byte, Block]
```

**Key design**: The Chunker is stored in a `FiberRef[Chunker]` so it can be overridden
per-fiber without changing the service wiring:

```scala
val current: FiberRef[Chunker] = FiberRef.unsafe.make(default)
def locally[R, E, A](chunker: Chunker)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
  current.locally(chunker)(effect)
```

## Pattern: ZChannel for Stateful Pipelines

When a pipeline needs internal mutable state (buffer, counters), use `ZChannel.readWith`
in a recursive loop. This is how the Chunker's `Incremental.pipeline` works:

```scala
ZPipeline.fromChannel {
  def loop(st: State): ZChannel[...] =
    ZChannel.readWith(
      (in: Chunk[Byte]) =>
        ZChannel.fromZIO(ZIO.fromEither(st.step(in)))
          .flatMap { case (st2, out) => ZChannel.write(out) *> loop(st2) },
      err => ZChannel.fail(err),
      _ => ZChannel.fromZIO(ZIO.fromEither(st.finish)).flatMap(...)
    )
  ZChannel.fromZIO(init).flatMap(loop)
}
```

**Rule**: Keep pure state machines separate from ZIO effects. The `ChunkerCore.State`
is a pure `Either`-returning state machine; the `ZChannel` layer just lifts it.

## Pattern: Sink-Based Ingestion

`BlobStore.put()` returns a `ZSink[Any, Throwable, Byte, Chunk[Byte], BlobWriteResult]`.
This allows the caller to stream bytes into the sink and get back a result:

```scala
val result: BlobWriteResult = ZStream.fromFile(path).run(blobStore.put(plan))
```

The `CasBlobStore.put()` implementation uses `ZSink.unwrapScoped` to set up resources
(queues, fibers) within the sink's lifecycle, then returns a simple fold sink.

## Pattern: Parallel Block Fetch (BlobStreamer)

For reads, the `BlobStreamer` composes DB-sourced block refs with parallel block fetches:

```scala
refs
  .buffer(window)              // bound DB cursor pressure
  .mapZIOPar(par)(ref =>       // fetch blocks concurrently
    blockStore.get(ref.key).runCollect)
  .flattenChunks               // flatten into byte stream
```

**Key**: `mapZIOPar` preserves input order, so bytes come out in manifest order.

## Pattern: Hashing Pass-Through

The `HashingZ.pipeline` creates a `ZPipeline` that updates a `MultiHasher` as a
side-effect while passing bytes through unchanged:

```scala
ZPipeline.mapChunksZIO { chunk =>
  ZIO.attempt(multi.update(chunk.toArray)).orDie.as(chunk)
}
```

## Pattern: Queue-Based Fan-Out (CasBlobStore)

`CasBlobStore.put()` uses bounded queues + `Take` for multi-stage pipelines:

1. `inputQ: Queue[Take[Throwable, Byte]]` — bytes from caller
2. Fiber: reads inputQ → runs ingest pipeline → chunker → per-block hashing → `blocksQ`
3. `blocksQ: Queue[Take[Throwable, CanonicalBlock]]` — canonical blocks
4. Fiber: reads blocksQ → runs `blockStore.putBlocks()` sink
5. `batchDone: Promise[Throwable, BlockBatchResult]` — block persist result

**Rule**: Always use `Take.end` to signal stream completion through queues. Always use
`Promise` to collect the final result from a background fiber.

## Best Practices

1. **Prefer `ZPipeline` over raw `ZChannel`** unless you need stateful buffering.
2. **Bridge typed errors at boundaries**: `chunker.pipeline.mapError(Chunker.toThrowable)`.
3. **Use `ZSink.foldLeftZIO`** for accumulating results (e.g., block manifest building).
4. **Use `ZStream.fromQueue` + `flattenTake`** to consume queue-backed streams.
5. **Bound all queues** — never use unbounded queues in production.
6. **Use `ZIO.scoped` / `ZSink.unwrapScoped`** for resource lifecycle in sinks.
7. **Prefer `mapZIOPar(n)` over `flatMapPar`** when order must be preserved.
8. **Use `buffer(n)` before parallel stages** to decouple producer/consumer rates.

## Anti-Patterns

- Do NOT use `ZStream.fromIterator` for large datasets (no backpressure).
- Do NOT forget `Take.end` when signaling stream completion through queues.
- Do NOT use `Unsafe.unsafe` outside of `FiberRef` initialization.
- Do NOT create unbounded queues — always use `Queue.bounded`.
- Do NOT mix `ZStream.run(sink)` with manual fiber management for the same stream.
