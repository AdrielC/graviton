# Execution Plan — CAS-Powered Roadmap

How the Transducer algebra becomes the engine for Graviton's Content-Addressed Storage.

**Status key**: [x] done on this branch, [ ] next up, [-] future

---

## Completed (This Branch)

- [x] **Phase 1 — Foundation**: BlobWriteResult extracted, root cleaned, AGENTS.md updated
- [x] **Phase 2 — Error model**: `GravitonError` hierarchy, `ChunkerCore.Err` integration, `StoreOps` extensions
- [x] **Phase 3 — Iron hardening**: `applyUnsafe` audit with SAFETY comments, 62 boundary value tests
- [x] **Transducer algebra**: `StateMerge` (Aux pattern), Record union, map fusion, `>>>` / `&&&`
- [x] **TransducerKit**: 12 production transducers (chunking, hashing, counting, dedup, batching, stats)
- [x] **IngestPipeline**: `countBytes >>> hashBytes >>> rechunk` — the critical composition proof
- [x] **Compilation targets**: `toSink`, `toTransducingSink`, `toPipeline`, `toChannel` (yields summary)
- [x] **257 tests passing**, all green

---

## Phase A — Wire Transducer into CasBlobStore

The existing `CasBlobStore.put()` manually orchestrates queues, fibers, and per-block
hashing in ~100 lines of imperative ZIO. Replace the inner pipeline with a composed
Transducer that's cleaner, testable in isolation, and produces a typed summary.

### A.1 — CAS Ingest Transducer

Build a `Transducer[Chunk[Byte], CanonicalBlock, IngestSummary]` that does:

```
Chunk[Byte] → countBytes → hashBytes → rechunk(blockSize) → perBlockKey → CanonicalBlock
```

Each stage:
| Stage | Transducer | State Fields |
|-------|-----------|-------------|
| Count bytes | `IngestPipeline.countBytes` | `totalBytes` |
| Stream hash | `IngestPipeline.hashBytes` | `digestHex`, `hashBytes` |
| Rechunk | `IngestPipeline.rechunk(blockSize)` | `blockCount`, `rechunkFill` |
| Block keying | new: `blockKeyDeriver` | `blocksKeyed` |

The `blockKeyDeriver` transducer takes each `Chunk[Byte]` block, hashes it independently
(per-block digest), derives a `BinaryKey.Block` via `KeyBits.create`, and emits a
`CanonicalBlock`. This is the bridge from pure byte processing to CAS semantics.

**Summary type**: `Record[(totalBytes ~ Long) & (digestHex ~ String) & ... & (blocksKeyed ~ Long)]`

### A.2 — Replace CasBlobStore Inner Pipeline

Refactor `CasBlobStore.put()` to:
1. Compile the ingest transducer to a `ZSink` via `toSink`
2. Feed the input stream through the sink
3. Use the summary to build the manifest and blob key
4. Persist via `BlockStore.putBlocks()` and `BlobManifestRepo.put()`

Before:
```scala
// ~100 lines of queues, fibers, promises, manual per-block hashing
```

After:
```scala
val ingest = CasIngest.pipeline(blockSize, algo)
val (summary, canonicalBlocks) = inputStream.run(ingest.toSink)
// summary.totalBytes, summary.digestHex, summary.blockCount — all named fields
```

### A.3 — Transducer-Based BlockStore Sink

Build a `Transducer[CanonicalBlock, StoredBlock, StoreSummary]` that:
- Calls `BlockStore.putBlock` for each canonical block (dedup check)
- Tracks `freshCount`, `duplicateCount`, `storedBytes`
- Builds `BlockManifestEntry` entries for the manifest

This requires a `Transducer.foldZIO` variant that supports effectful steps
(the block store is effectful). Design options:
- **Option 1**: `TransducerZIO[-I, +O, S, -R, +E]` — a separate effectful trait
- **Option 2**: Keep `Transducer` pure, compile to `ZSink` that handles effects
- **Option 3**: `Transducer.fromSink(sink)` — wrap an existing ZSink as a transducer

**Recommendation**: Option 2 — the ingest transducer stays pure (testable!), and the
effectful block persistence is a separate ZSink that consumes `CanonicalBlock`s. The
pure transducer produces the blocks; the effectful sink stores them.

```scala
val (ingestSummary, blocks) = byteStream.run(ingestTransducer.toSink)
val storeResult             = ZStream.fromChunk(blocks).run(blockStore.putBlocks())
val blobKey                 = BinaryKey.blob(KeyBits.create(algo, digest, ingestSummary.totalBytes))
```

---

## Phase B — Manifest Construction via Transducer

### B.1 — Manifest Builder Transducer

Build a `Transducer[CanonicalBlock, ManifestEntry, Record[("entries" ~ Long) & ("manifestSize" ~ Long)]]`:
- For each canonical block, compute its `BlobOffset` span
- Emit a `ManifestEntry` with the block's `BinaryKey` and span
- Track running offset and entry count in the summary

### B.2 — Full Ingest Pipeline

Compose the full CAS ingest:
```
bytes → count >>> hash >>> rechunk → blockKey → manifestEntry
                                        ↓
                             CanonicalBlock (for BlockStore)
```

Using `&&&` to split the rechunked blocks into two parallel paths:
1. Block keying (for persistence)
2. Manifest entry building (for manifest)

### B.3 — Persist Advertised/Confirmed Attributes

The ingest summary becomes the source of truth for `BinaryAttributes`:
```scala
val attrs = BinaryAttributes.empty
  .confirmSize(FileSize.unsafe(summary.totalBytes))
  .confirmDigest(algo, HexLower.applyUnsafe(summary.digestHex))
  .confirmChunkCount(ChunkCount.unsafe(summary.blockCount))
```

This closes the AGENTS.md TODO: "Persist advertised/confirmed attributes with manifests."

---

## Phase C — Verification & Integrity

### C.1 — Block Verification Transducer

Build a `Transducer[Chunk[Byte], VerifyResult, Record[("verified" ~ Long) & ("failed" ~ Long)]]`:
- Re-hash each block
- Compare with the expected `BinaryKey.Block` from the manifest
- Emit pass/fail per block

Use case: verify stored blocks haven't been corrupted.

```scala
val verify = IngestPipeline.rechunk(blockSize) >>> blockVerifier(manifest)
val (summary, results) = blockStream.run(verify.toSink)
assert(summary.failed == 0L)
```

### C.2 — Blob Integrity Check

Full blob verification pipeline:
```
storedBytes → rechunk → verify each block against manifest → verify total hash
```

Composed as:
```scala
val check = IngestPipeline.countBytes &&& IngestPipeline.hashBytes() &&& blockVerifier(manifest)
```

Summary: `totalBytes`, `digestHex`, `verified`, `failed` — all named fields.

---

## Phase D — CDC Chunker as Transducer

### D.1 — FastCDC Transducer

Port the existing `ChunkerCore.Mode.FastCdc` into a `Transducer[Chunk[Byte], Chunk[Byte], CdcSummary]`:
- Rolling hash boundary detection at the chunk level
- Bounded buffer (min/avg/max block size)
- Summary: `blockCount`, `avgBlockSize`, `boundaryReasons` (map of reason → count)

This replaces `ChunkerCore` + `Chunker` pipeline for the ingest path, making CDC
composable with hashing and counting via `>>>`:

```scala
val cdcIngest = IngestPipeline.countBytes >>> IngestPipeline.hashBytes() >>> cdcChunker(min, avg, max)
```

### D.2 — Anchored CDC

Extend CDC with anchor detection (DFA/Aho-Corasick prefix matching):
- When an anchor sequence is found, force a boundary
- Boundary reason: `"anchor"` / `"roll"` / `"max"` / `"eof"`

This is a Transducer composition: `anchorDetector >>> cdcChunker`.

---

## Phase E — Deduplication

### E.1 — Block Dedup Transducer

Build a `Transducer[CanonicalBlock, CanonicalBlock, Record[("fresh" ~ Long) & ("duplicate" ~ Long)]]`:
- Check each block key against a `Set[BinaryKey.Block]` (in-memory) or a bloom filter
- Pass through fresh blocks, drop duplicates
- Summary tracks dedup ratio

### E.2 — Cross-Blob Dedup Index

Build a `Transducer[BinaryKey.Block, Boolean, Record["indexSize" ~ Long]]` backed by a
persistent rolling-hash index:
- Input: block keys from multiple blobs
- Output: `true` if the block is new (not in any previous blob)
- Summary: index size

This requires an effectful transducer or a two-phase approach:
1. Pure transducer checks local bloom filter
2. Effectful second pass checks persistent index

---

## Phase F — Compression & Encryption Frames

### F.1 — Compression Transducer

`Transducer[Chunk[Byte], Chunk[Byte], Record[("compressedBytes" ~ Long) & ("ratio" ~ Double)]]`:
- Compress each block with zstd
- Track compression ratio in summary
- Compose after rechunker: `rechunk >>> compress`

### F.2 — Frame Emitter Transducer

`Transducer[Chunk[Byte], BlockFrame, Record["frameCount" ~ Long]]`:
- Wrap each compressed block in the self-describing frame format
- Add magic bytes, algo IDs, sizes, optional nonce/AAD
- This is where `FrameSynthesis` config drives the transducer behavior

### F.3 — Full Ingest Pipeline (v2)

```
bytes → count >>> hash → CDC → compress → encrypt → frame → persist
```

All composed via `>>>` with the full summary:
```scala
summary.totalBytes       // from countBytes
summary.digestHex        // from hashBytes  
summary.blockCount       // from CDC
summary.compressedBytes  // from compress
summary.ratio            // from compress
summary.frameCount       // from frame emitter
```

---

## Phase G — Retrieval & Streaming

### G.1 — Block Reassembly Transducer

Build a `Transducer[BlockRef, Chunk[Byte], Record[("blocksRead" ~ Long) & ("bytesRead" ~ Long)]]`:
- Fetch each block from `BlockStore` (effectful — uses `toSink` pattern)
- Emit block bytes in manifest order
- Track read stats

This replaces `BlobStreamer.streamBlob` with a transducer-based approach.

### G.2 — Decompression/Decryption Transducers

Inverse of F.1/F.2:
- `Transducer[BlockFrame, Chunk[Byte], ...]` — unwrap frames
- `Transducer[Chunk[Byte], Chunk[Byte], ...]` — decompress

Compose: `readFrames >>> decompress >>> reassemble`

---

## Phase H — Operational Excellence

### H.1 — Ingest Metrics Transducer

Compose with any pipeline for production observability:
```scala
val monitored = ingestPipeline &&& throughputMonitor &&& latencyTracker
```

Summary fields: `bytesPerSec`, `p99Latency`, `blockRate`, etc.
Wire into `MetricsRegistry` via `toSink` callback.

### H.2 — Ingestion Bomb Protection

`Transducer[Chunk[Byte], Chunk[Byte], Record[("totalSeen" ~ Long) & ("rejected" ~ Boolean)]]`:
- Track total bytes seen
- If total exceeds configured limit, stop emitting (transition to reject mode)
- Summary tells caller whether the upload was rejected

Compose at the front of any ingest pipeline:
```scala
val safePipeline = bombGuard(maxBytes = 10.gigabytes) >>> ingestPipeline
```

### H.3 — Rate Limiting Transducer

`Transducer[Chunk[Byte], Chunk[Byte], Record["throttled" ~ Long]]`:
- Token bucket or leaky bucket rate limiting
- Compose at the front for upload throttling

---

## Verification Checklist

After each phase:

```bash
# Code compiles and formats
TESTCONTAINERS=0 ./sbt scalafmtAll compile

# All tests pass
TESTCONTAINERS=0 ./sbt test

# Docs build
./sbt docs/mdoc checkDocSnippets
```

---

## Architecture: How It All Fits

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

Every box in the Ingest/Retrieval/Verify paths is a `Transducer` with typed Record state.
Composition via `>>>` and `&&&` produces a single transducer whose summary has ALL fields
from ALL stages accessible by name. Memory is always O(blockSize), never O(stream).
