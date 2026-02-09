# Transducer Algebra

Graviton's **Transducer algebra** is the typed, composable pipeline engine that powers CAS ingest, verification, and retrieval. Every stage in the pipeline is a `Transducer` with typed input, output, and a Record-based summary — and stages compose via `>>>` (sequential) and `&&&` (fanout) with automatic state merging.

## Why Transducers?

Traditional streaming pipelines couple logic with orchestration. Graviton's transducers separate the *what* (chunking, hashing, counting, dedup) from the *how* (ZSink, ZPipeline, ZChannel). This means:

- **Testable in isolation** — each transducer can be tested with `runChunk` without ZIO
- **Composable** — `>>>` chains stages sequentially, `&&&` fans out the same input
- **Typed summaries** — every transducer produces a named `Record` summary accessible by field name
- **Bounded memory** — buffers at most one block; total memory is O(blockSize), never O(stream)

### Single-pass design

When stages are composed via `>>>`, bytes flow through the chain in a single pass — `countBytes >>> hashBytes >>> rechunk` processes each input chunk once in sequence without buffering the stream or seeking backwards. The `&&&` fanout operator delivers each element to both branches, so each element is *processed* by two stages but the source is still consumed exactly once.

::: warning Per-block keying requires a second hash
The blob-level hash (computed by `hashBytes`) sees every byte as it passes through. After `rechunk` produces blocks, a separate `blockKeyDeriver` stage hashes each block independently to derive its `BinaryKey.Block`. This is a second hash computation over data that is still in memory — not a re-read of the source stream, but a distinct operation required by the CAS design (blob key != block key).
:::

## Core Types

```scala
trait Transducer[-I, +O, S]:
  def init: S
  def step(state: S, input: I): (S, Chunk[O])
  def finish(state: S): (S, Chunk[O])
```

| Type param | Role |
|-----------|------|
| `I` | Input element type (e.g. `Chunk[Byte]`) |
| `O` | Output element type (e.g. `CanonicalBlock`) |
| `S` | State type — a `kyo.Record` with named fields |

## Composition Operators

### Sequential (`>>>`)

Chain two transducers so the output of the first feeds the input of the second. Each input element is processed once in sequence — no buffering, no re-reads. The summary merges both states into a single Record:

```scala
val pipeline = countBytes >>> hashBytes >>> rechunk(blockSize)
// Summary: Record[("totalBytes" ~ Long) & ("digestHex" ~ String) & ("blockCount" ~ Long)]
```

Internally `>>>` calls `self.step` then feeds each output into `that.step`. Hot state is a tuple `(left.Hot, right.Hot)` — primitives only.

### Fanout (`&&&`)

Send the same input element to both transducers. The source stream is consumed once, but each element is processed by both branches:

```scala
val check = countBytes &&& hashBytes &&& blockVerifier(manifest)
// Summary includes totalBytes, digestHex, verified, failed — all named fields
```

::: info `&&&` is not parallel execution
`&&&` hands the same input to two `step` calls synchronously inside one fiber. It doesn't spawn parallel fibers or broadcast the stream — it's a logical fanout, not a concurrency primitive. Use `ZStream.broadcast` if you need actual multi-fiber parallelism.
:::

### StateMerge

The `StateMerge` typeclass (with `Aux` pattern) automatically merges Record states when composing transducers. Unit states are identity elements; non-unit states become paired Records.

## Transducers (TransducerKit)

### Implemented today

These transducers are implemented and tested in `IngestPipeline` and `TransducerKit`:

| Transducer | Input | Output | Summary Fields |
|-----------|-------|--------|---------------|
| `countBytes` | `Chunk[Byte]` | `Chunk[Byte]` | `totalBytes: Long` |
| `hashBytes(algo)` | `Chunk[Byte]` | `Chunk[Byte]` | `digestHex: String`, `hashBytes: Long` |
| `rechunk(size)` | `Chunk[Byte]` | `Chunk[Byte]` | `blockCount: Long`, `rechunkFill: Int` |
| `counter[A]` | `A` | `Long` | `count: Long` |
| `byteCounter` | `Chunk[Byte]` | `Long` | `totalBytes: Long` |
| `blockCounter` | `Chunk[Byte]` | `Chunk[Byte]` | `blockCount: Long` |
| `dedup(key)` | `A` | `A` | `uniqueCount: Long`, `duplicateCount: Long` |
| `batch(size)` | `A` | `Chunk[A]` | `batchCount: Long`, `batchSize: Int` |
| `groupBy(key)` | `A` | `(K, Chunk[A])` | `groupCount: Long` |
| `exponentialMovingAvg` | `Double` | `Double` | `ema: Double`, `emaSamples: Long` |
| `minMax` | `A` | `A` | `min: Option[A]`, `max: Option[A]` |
| `reservoirSample` | `A` | `Vector[A]` | `reservoir: Vector[A]`, `seen: Long` |
| `chunkDigest` | `Chunk[Byte]` | `(Chunk[Byte], Digest)` | (stateless) |

### Planned (roadmap)

These are described in the [execution plan](../architecture.md) but not yet implemented as `Transducer` instances:

| Transducer | Phase | Description |
|-----------|-------|------------|
| `blockKeyDeriver` | A | Per-block hash + `BinaryKey.Block` derivation |
| `compress` | F | Zstd compression with ratio tracking |
| `frameEmitter` | F | Self-describing frame format wrapping |
| `bombGuard` | H | Ingestion bomb protection |
| `manifestBuilder` | B | Manifest entry construction from blocks |
| `blockVerifier` | C | Re-hash verification against manifest |
| `throughputMonitor` | H | Real-time throughput gauge |

## Compilation Targets

A transducer can be compiled to multiple ZIO abstractions:

```scala
val transducer = countBytes >>> hashBytes >>> rechunk(blockSize)

// Compile to different targets
val sink: ZSink[Any, Nothing, Chunk[Byte], Nothing, Summary] = transducer.toSink
val pipeline: ZPipeline[Any, Nothing, Chunk[Byte], Chunk[Byte]]  = transducer.toPipeline
val channel: ZChannel[...]                                         = transducer.toChannel
```

| Target | Use Case |
|--------|---------|
| `toSink` | Final consumption — run a stream, get the summary |
| `toPipeline` | Mid-stream transformation — pass through to next stage |
| `toTransducingSink` | Combined: transform AND summarize |
| `toChannel` | Low-level: direct ZChannel integration |

## The Ingest Pipeline

The critical composition proof — the CAS ingest pipeline:

```scala
val ingestPipeline = 
  IngestPipeline.countBytes >>> 
  IngestPipeline.hashBytes() >>> 
  IngestPipeline.rechunk(blockSize)

// Use it:
val (summary, blocks) = byteStream.run(ingestPipeline.toSink)
summary.totalBytes   // Long — named field access
summary.digestHex    // String
summary.blockCount   // Long
```

### Full CAS Ingest (Phase A target)

```
bytes → count >>> hash >>> rechunk → blockKey → manifestEntry
                                        ↓
                             CanonicalBlock (for BlockStore)
```

```scala
val casIngest = CasIngest.pipeline(blockSize, algo)
val (summary, canonicalBlocks) = inputStream.run(casIngest.toSink)
// summary.totalBytes, summary.digestHex, summary.blockCount — all named
```

## Verification Pipeline

Block integrity checking composes the same transducers:

```scala
val verify = IngestPipeline.rechunk(blockSize) >>> blockVerifier(manifest)
val (summary, results) = blockStream.run(verify.toSink)
assert(summary.failed == 0L)
```

Full blob verification:

```scala
val check = IngestPipeline.countBytes &&& IngestPipeline.hashBytes() &&& blockVerifier(manifest)
// Summary: totalBytes, digestHex, verified, failed
```

## Architecture Diagram

```
                     ┌─────────────────────────────────────────────┐
                     │              Transducer Algebra             │
                     │  StateMerge · Record union · Map fusion     │
                     │  >>> (sequential) · &&& (fanout)            │
                     │  toSink · toPipeline · toTransducingSink    │
                     └────────────┬────────────────────────────────┘
                                  │
          ┌───────────────────────┼───────────────────────┐
          │                       │                       │
 ┌────────▼──────────┐  ┌────────▼──────────┐  ┌────────▼──────────┐
 │   Ingest Path     │  │  Retrieval Path   │  │  Verify Path      │
 │                   │  │                   │  │                   │
 │ countBytes        │  │ readFrames        │  │ rechunk           │
 │ >>> hashBytes     │  │ >>> decompress    │  │ >>> rehash        │
 │ >>> CDC/rechunk   │  │ >>> reassemble    │  │ >>> compare       │
 │ >>> blockKey      │  │                   │  │                   │
 │ >>> compress      │  │ Summary:          │  │ Summary:          │
 │ >>> frame         │  │  blocksRead       │  │  verified         │
 │                   │  │  bytesRead        │  │  failed           │
 │ Summary:          │  │                   │  │  totalBytes       │
 │  totalBytes       │  └────────┬──────────┘  └────────┬──────────┘
 │  digestHex        │           │                       │
 │  blockCount       │           │                       │
 │  compressedBytes  │           │                       │
 │  frameCount       │           ▼                       ▼
 └────────┬──────────┘     ZStream[Byte]           VerifyResult
          │
          ▼
 ┌────────────────────┐     ┌─────────────────────┐
 │    BlockStore      │     │  BlobManifestRepo   │
 │  (S3/FS/Rocks)     │     │  (Postgres)         │
 └────────────────────┘     └─────────────────────┘
```

::: info What's implemented today
In the **Ingest Path**, `countBytes`, `hashBytes`, and `rechunk` are production Transducers. The remaining stages (`blockKey`, `compress`, `frame`) are implemented as standalone helpers but not yet wired into the `>>>` chain in production — `CasBlobStore.put()` still orchestrates those stages via queues and fibers. Phase A of the [execution plan](https://github.com/AdrielC/graviton) replaces the hand-written orchestration with the composed Transducer.

The **Retrieval Path** and **Verify Path** are design targets; retrieval currently uses `BlobStreamer.streamBlob` (parallel block fetch via `mapZIOPar`), not a Transducer chain.
:::

### Pass semantics

The `>>>` composition is single-pass: each input element flows through every stage exactly once, in sequence, within a single fiber. No intermediate collections, no re-reads.

However, the CAS ingest design inherently requires **two distinct hash computations** for each byte:

1. **Blob-level hash** (`hashBytes`) — incremental hash of the entire stream to derive the `BinaryKey.Blob`
2. **Per-block hash** (`blockKeyDeriver`) — hash of each block's bytes after rechunking to derive per-block `BinaryKey.Block` keys

These are separate operations with different scopes. The blob hash covers all bytes; the block hash covers one block. The block's bytes are still in memory when `rechunk` emits them, so no data is re-read from the source — but the bytes are hashed twice at different granularities. This is fundamental to content-addressed storage (the blob key and block keys serve different purposes and cannot share a single hash).

The `&&&` fanout also processes each element once per branch. It is a synchronous logical fan-out (same fiber, two `step` calls), not a concurrent broadcast.

## Roadmap

The Transducer algebra is the foundation for upcoming pipeline phases:

| Phase | Status | Description |
|-------|--------|------------|
| **A** — CAS Ingest Transducer | Next | Wire transducer into CasBlobStore.put() |
| **B** — Manifest Construction | Planned | Manifest builder transducer + attribute persistence |
| **C** — Verification & Integrity | Planned | Block and blob verification pipelines |
| **D** — CDC Chunker as Transducer | Planned | Port FastCDC to transducer form |
| **E** — Deduplication | Planned | Block dedup and cross-blob dedup index |
| **F** — Compression & Encryption | Planned | Frame format with self-describing headers |
| **G** — Retrieval & Streaming | Planned | Block reassembly and decompression |
| **H** — Operational Excellence | Planned | Metrics, bomb protection, rate limiting |

## See Also

- **[Scans & Events](./scans.md)** — The Scan algebra that inspired Transducers
- **[Binary Streaming Guide](../guide/binary-streaming.md)** — End-to-end ingest walkthrough
- **[Architecture](../architecture.md)** — System-level view
- **[Pipeline Explorer](../pipeline-explorer.md)** — Interactive transducer visualization
