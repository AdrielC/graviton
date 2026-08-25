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
  type Hot
  def initHot: Hot
  def step(h: Hot, input: I): (Hot, Chunk[O])
  def flush(h: Hot): (Hot, Chunk[O])
  def toSummary(h: Hot): S
```

| Type param | Role |
|-----------|------|
| `I` | Input element type (e.g. `Chunk[Byte]`) |
| `O` | Output element type (e.g. `KeyedBlock` / `VerifyResult`) |
| `S` | Summary type — typically a `kyo.Record` with named fields (materialized at boundaries from `Hot`) |

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
val check = countBytes &&& hashBytes &&& BlockVerify.verifier(expectedBlockKeys)
// Summary includes totalBytes, digestHex, verified, failed — all named fields
```

::: info `&&&` is not parallel execution
`&&&` hands the same input to two `step` calls synchronously inside one fiber. It doesn't spawn parallel fibers or broadcast the stream — it's a logical fanout, not a concurrency primitive. Use `ZStream.broadcast` if you need actual multi-fiber parallelism.
:::

### StateMerge

The `StateMerge` typeclass (with `Aux` pattern) automatically merges Record states when composing transducers. Unit states are identity elements; non-unit states become paired Records.

## Transducers (`IngestPipeline`, `Transducers`, and friends)

### Implemented today

These transducers are implemented in `graviton.core.scan` (primarily `IngestPipeline`, `Transducers`, `CasIngest`, `BombGuard`, `ThroughputMonitor`, `BlockVerify`):

| Transducer | Input | Output | Summary Fields |
|-----------|-------|--------|---------------|
| `countBytes` | `Chunk[Byte]` | `Chunk[Byte]` | `totalBytes: Long` |
| `hashBytes(algo)` | `Chunk[Byte]` | `Chunk[Byte]` | `digestHex: String`, `hashBytes: Long` |
| `rechunk(size)` | `Chunk[Byte]` | `Chunk[Byte]` | `blockCount: Long`, `rechunkFill: Int` |
| `CasIngest.blockKeyDeriver` | `Chunk[Byte]` | `KeyedBlock` | `blocksKeyed: Long` |
| `BombGuard(maxBytes)` | `Chunk[Byte]` | `Chunk[Byte]` | `totalSeen: Long`, `rejected: Boolean`, `maxBytes: Long` |
| `ThroughputMonitor()` | `Chunk[Byte]` | `Chunk[Byte]` | `monitoredBytes`, `monitoredChunks`, `elapsedNanos`, `bytesPerSecond` |
| `BlockVerify.verifier` | `Chunk[Byte]` | `VerifyResult` | `verified: Long`, `failed: Long`, `errors: Long` |
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

These appear in design documents but are **not** full production transducers yet, or are not wired into default ingest:

| Transducer / stage | Description |
|-----------|------------|
| `compress` | Zstd (or similar) as a transducer with ratio tracking in summaries |
| `manifestBuilder` | Emit manifest entries as a transducer stage (today manifests are built inside `CasBlobStore` / batch results) |
| `frameEmitter` | Self-describing frame format as a composed transducer chain |

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

### Full CAS ingest (library vs `CasBlobStore`)

`CasIngest.pipeline` composes count/hash/rechunk/block-key stages for **library and test** use. **`CasBlobStore.put`** uses a **chunker** (`Chunker` / `FiberRef`) plus **`CasIngest.blockKeyDeriver`** as a `ZPipeline` after chunking, and computes the **blob-level** digest incrementally alongside the stream, so an application's exact `>>>` chain may differ while producing the same CAS semantics.

```scala
val casIngest = CasIngest.pipeline(blockSize, algo)
val (summary, keyedBlocks) = inputStream.run(casIngest.toSink)
// summary includes totalBytes, digestHex, blockCount, blocksKeyed — named fields
```

## Verification Pipeline

Block integrity checking composes the same transducers:

```scala
val verify = IngestPipeline.rechunk(blockSize) >>> BlockVerify.verifier(expectedBlockKeys)
val (summary, results) = blockStream.run(verify.toSink)
assert(summary.failed == 0L)
```

Full blob verification:

```scala
val check = IngestPipeline.countBytes &&& IngestPipeline.hashBytes() &&& BlockVerify.verifier(expectedBlockKeys)
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
**Ingest:** `countBytes`, `hashBytes`, `rechunk`, **`CasIngest.blockKeyDeriver`**, **`BombGuard`**, and **`ThroughputMonitor`** are implemented transducers. **`CasBlobStore`** wires **per-block keying** via `blockKeyDeriver.toPipeline` after the chunker; blob-level hashing uses an incremental `Hasher` in the same ingest path (not always expressed as a single `>>>` chain in source). **Compression** and **aggregate frame layout** in `BlockFramer` are partial (see [Manifests & Frames](../manifests-and-frames.md)).

**Retrieval** uses `BlobStreamer` / `BlobStore.get`, not a transducer chain. **Verify** uses `BlockVerify` transducers where you compose them explicitly.
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
| **A** — CAS ingest | **In progress** | Per-block keying transducer wired in `CasBlobStore`; optional `CasIngest.pipeline` for all-in-one experiments |
| **B** — Manifest construction | **Partial** | Manifests persisted via `BlobManifestRepo` / batch results; dedicated `manifestBuilder` transducer still roadmap |
| **C** — Verification & integrity | **Partial** | `BlockVerify` transducers implemented; full operational verification tooling still evolving |
| **D** — CDC chunker as transducer | Planned | Port FastCDC (and related) to first-class transducer/chunker integration |
| **E** — Deduplication | **Partial** | Block dedup at `BlockStore`; cross-blob / rolling-hash index roadmap |
| **F** — Compression & encryption | Not exposed | Requires paired streaming encode/decode and key-provider implementations before entering the public plan algebra |
| **G** — Retrieval & streaming | **Partial** | Block reassembly via `BlobStreamer`; decompression-as-transducer for reads roadmap |
| **H** — Operational excellence | **Partial** | `BombGuard`, `ThroughputMonitor`, metrics decorators; rate limiting and hardening roadmap |

## See Also

- **[Scans & Events](./scans.md)** — The Scan algebra that inspired Transducers
- **[Binary Streaming Guide](../guide/binary-streaming.md)** — End-to-end ingest walkthrough
- **[Architecture](../architecture.md)** — System-level view
- **[Connect Your Server](../demo.md)** to operate the implemented HTTP storage lifecycle against an endpoint you provide
