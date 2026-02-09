# Transducer Algebra

Graviton's **Transducer algebra** is the typed, composable pipeline engine that powers CAS ingest, verification, and retrieval. Every stage in the pipeline is a `Transducer` with typed input, output, and a Record-based summary — and stages compose via `>>>` (sequential) and `&&&` (fanout) with automatic state merging.

## Why Transducers?

Traditional streaming pipelines couple logic with orchestration. Graviton's transducers separate the *what* (chunking, hashing, counting, dedup) from the *how* (ZSink, ZPipeline, ZChannel). This means:

- **Testable in isolation** — each transducer can be tested with `runChunk` without ZIO
- **Composable** — `>>>` chains stages sequentially, `&&&` fans out the same input
- **Typed summaries** — every transducer produces a named `Record` summary accessible by field name
- **Memory-safe** — always O(blockSize), never O(stream)

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

Chain two transducers so the output of the first feeds the input of the second. The summary merges both states into a single Record:

```scala
val pipeline = countBytes >>> hashBytes >>> rechunk(blockSize)
// Summary: Record[("totalBytes" ~ Long) & ("digestHex" ~ String) & ("blockCount" ~ Long)]
```

### Fanout (`&&&`)

Send the same input to multiple transducers in parallel, collecting all outputs:

```scala
val check = countBytes &&& hashBytes &&& blockVerifier(manifest)
// Summary includes totalBytes, digestHex, verified, failed — all named fields
```

### StateMerge

The `StateMerge` typeclass (with `Aux` pattern) automatically merges Record states when composing transducers. Unit states are identity elements; non-unit states become paired Records.

## Production Transducers (TransducerKit)

Graviton ships 12 production-ready transducers:

| Transducer | Input | Output | Summary Fields |
|-----------|-------|--------|---------------|
| `countBytes` | `Chunk[Byte]` | `Chunk[Byte]` | `totalBytes: Long` |
| `hashBytes` | `Chunk[Byte]` | `Chunk[Byte]` | `digestHex: String`, `hashBytes: Long` |
| `rechunk(size)` | `Chunk[Byte]` | `Chunk[Byte]` | `blockCount: Long`, `rechunkFill: Int` |
| `blockKeyDeriver` | `Chunk[Byte]` | `CanonicalBlock` | `blocksKeyed: Long` |
| `dedup` | `CanonicalBlock` | `CanonicalBlock` | `fresh: Long`, `duplicate: Long` |
| `compress` | `Chunk[Byte]` | `Chunk[Byte]` | `compressedBytes: Long`, `ratio: Double` |
| `frameEmitter` | `Chunk[Byte]` | `BlockFrame` | `frameCount: Long` |
| `bombGuard(max)` | `Chunk[Byte]` | `Chunk[Byte]` | `totalSeen: Long`, `rejected: Boolean` |
| `manifestBuilder` | `CanonicalBlock` | `ManifestEntry` | `entries: Long`, `manifestSize: Long` |
| `blockVerifier` | `Chunk[Byte]` | `VerifyResult` | `verified: Long`, `failed: Long` |
| `throughputMonitor` | `Chunk[Byte]` | `Chunk[Byte]` | `bytesPerSec: Double` |
| `latencyTracker` | any | any | `p99Latency: Double` |

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
