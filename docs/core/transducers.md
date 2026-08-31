# Transducer Algebra

Graviton's **Transducer algebra** is a typed, composable library for pure chunk processing and explicit verification pipelines. The production CAS upload path reuses `CasIngest.blockKeyDeriver`, but keeps orchestration, chunker selection, hashing, bounded queues, manifest spooling, and backend effects in `CasBlobStore`. Retrieval uses `BlobStreamer`, not a transducer pipeline.

## Why Transducers?

Traditional streaming pipelines couple logic with orchestration. Graviton's transducers separate the *what* (chunking, hashing, counting, dedup) from the *how* (ZSink, ZPipeline, ZChannel). This means:

- **Testable in isolation** — each transducer can be tested with `runChunk` without ZIO
- **Composable** — `>>>` chains stages sequentially, `&&&` fans out the same input
- **Typed summary shape** — transducers describe summary fields in their result type
- **Streaming compilation** — `toPipeline` and `toChannel` can process a stream without collecting all outputs

`runChunk` and `toSink` return a `Chunk` containing every output. Their retained memory is therefore O(output size), and they are appropriate only for bounded inputs or tests. `toTransducingSink` keeps only transducer state but discards transformed outputs. Production upload uses `toPipeline` for per-block keying.

::: info Stable aggregate summaries
Individual low-level stages can expose `kyo.Record` summaries. The recommended aggregate entry points, `IngestPipeline.countHashRechunkSummary` and `CasIngest.pipelineSummary`, map terminal state to explicit case classes with derived ZIO Blocks schemas. Their named fields are ordinary Scala accessors and their complete suites run on Scala 3.8. The v0.7 `countHashRechunk` and `pipeline` methods remain as deprecated binary-compatibility shims with their original Record-shaped return types. Authors of new aggregate APIs should use `mapSummary` to expose an explicit product rather than publishing a mixed-field dynamic Record as a stable contract.
:::

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
| `S` | Summary type, materialized only at boundaries from `Hot`; aggregate public APIs use explicit schema-backed products |

## Composition Operators

### Sequential (`>>>`)

Chain two transducers so the output of the first feeds the input of the second. Each input element is processed once in sequence with no buffering or re-reads. The generic combinator merges its summary shapes. A public aggregate pipeline can then use `mapSummary` to expose a stable product:

```scala
val pipeline = IngestPipeline.countHashRechunkSummary(blockSize)
// Summary: IngestPipeline.Summary
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

The `StateMerge` typeclass (with `Aux` pattern) merges stage states when composing transducers. Unit states are identity elements, Records union with Records, and other products pair. `mapSummary` projects the resulting terminal state into a stable public type without changing the streaming hot path.

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
val sink: ZSink[Any, Nothing, Chunk[Byte], Nothing, (Summary, Chunk[Chunk[Byte]])] = transducer.toSink
val pipeline: ZPipeline[Any, Nothing, Chunk[Byte], Chunk[Byte]]  = transducer.toPipeline
val channel: ZChannel[...]                                         = transducer.toChannel
```

| Target | Use Case |
|--------|---------|
| `toSink` | Bounded input or tests; returns the summary and materializes every output |
| `toPipeline` | Mid-stream transformation — pass through to next stage |
| `toTransducingSink` | Summarize while discarding transformed outputs |
| `toChannel` | Low-level: direct ZChannel integration |

## The library ingest pipeline

This bounded example proves composition. It is not the production `CasBlobStore` orchestrator:

```scala
val ingestPipeline = 
  IngestPipeline.countHashRechunkSummary(blockSize)

// Use it:
val (summary, blocks) = byteStream.run(ingestPipeline.toSink)
assert(summary.totalBytes >= 0L)
// `toSink` collects every output, so this form is only for bounded inputs.
```

### Full CAS ingest (library vs `CasBlobStore`)

`CasIngest.pipelineSummary` composes count/hash/rechunk/block-key stages for **library and test** use. **`CasBlobStore.put`** uses a **chunker** (`Chunker` / `FiberRef`) plus **`CasIngest.blockKeyDeriver`** as a `ZPipeline` after chunking, and computes the **blob-level** digest incrementally alongside the stream, so an application's exact `>>>` chain may differ while producing the same CAS semantics.

```scala
val casIngest = CasIngest.pipelineSummary(blockSize, algo)
val (summary, keyedBlocks) = inputStream.run(casIngest.toSink)
assert(summary.blocksKeyed == keyedBlocks.length.toLong)
// The terminal summary is a schema-backed CasIngest.Summary.
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

## Architecture diagram

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
 │ countBytes        │  │ BlobStreamer      │  │ rechunk           │
 │ >>> hashBytes     │  │ ordered blocks    │  │ >>> rehash        │
 │ >>> rechunk       │  │                   │  │ >>> compare       │
 │ >>> blockKey      │  │ not transducers   │  │                   │
 │                   │  │                   │  │ Summary:          │
 │ Summary:          │  │                   │  │  verified         │
 │                   │  │  bytesRead        │  │  failed           │
 │ Summary:          │  │                   │  │  totalBytes       │
 │  totalBytes       │  └────────┬──────────┘  └────────┬──────────┘
 │  digestHex        │           │                       │
 │  blockCount       │           │                       │
 │                   │           │                       │
 │                   │           ▼                       ▼
 └────────┬──────────┘     ZStream[Byte]           VerifyResult
          │
          ▼
 ┌────────────────────┐     ┌─────────────────────┐
 │    BlockStore      │     │  BlobManifestRepo   │
 │ (filesystem/S3/    │     │ (filesystem or      │
 │ replica/erasure)   │     │  PostgreSQL)         │
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
| **A** — CAS ingest | **Production path and typed aggregate summary implemented** | `CasBlobStore` streams through the selected chunker and `blockKeyDeriver`; bounded library composition uses schema-backed `IngestPipeline.Summary` and `CasIngest.Summary` products |
| **B** — Manifest construction | **Partial** | Manifests persisted via `BlobManifestRepo` / batch results; dedicated `manifestBuilder` transducer still roadmap |
| **C** — Verification & integrity | **Implemented in two layers** | `BlockVerify` supports explicit transducer composition; runtime blob verification is implemented separately by the store and API |
| **D** — CDC chunker as transducer | Planned | Port FastCDC (and related) to first-class transducer/chunker integration |
| **E** — Deduplication | **Implemented in storage** | Content-addressed block keys provide cross-blob reuse; there is no separate rolling-hash index |
| **F** — Compression & encryption | Not exposed | Requires paired streaming encode/decode and key-provider implementations before entering the public plan algebra |
| **G** — Retrieval & streaming | **Partial** | Block reassembly via `BlobStreamer`; decompression-as-transducer for reads roadmap |
| **H** — Operational integration | **Separated by design** | `BombGuard` and `ThroughputMonitor` are library transducers. Runtime admission, rate limits, metrics, and security live outside the pure transducer algebra. |

## See Also

- **[Scans & Events](./scans.md)** — The Scan algebra that inspired Transducers
- **[Binary Streaming Guide](../guide/binary-streaming.md)** — End-to-end ingest walkthrough
- **[Architecture](../architecture.md)** — System-level view
- **[Connect Your Server](../demo.md)** to operate the implemented HTTP storage lifecycle against an endpoint you provide
