---
layout: doc
title: Pipeline Explorer
---

# Pipeline Explorer

Graviton's ingest path turns bytes into bounded blocks, hashes those blocks, and writes a manifest that can reconstruct and verify the original blob. The browser explorer streams real local files through a dedicated Scala.js analyzer and compares exact block identities without claiming persistence.

<PipelinePlayground />

## How this maps to the runtime

| Browser stage | Runtime counterpart | Boundary |
| --- | --- | --- |
| Browser `File` stream | HTTP or CLI byte stream | Both remain streams; the browser tool owns one bounded block at a time and caps result metadata. |
| PDF structures, FastCDC, or fixed ranges | `Chunker.fixed`, `Chunker.fastCdc`, or PDF-aware ingest | The visitor controls the comparison profile. A persisted server manifest remains authoritative. |
| Incremental SHA-256 | Incremental blob hasher and per-block key derivation | Both whole-file and block digests are computed without collecting the complete file. |
| Exact cross-file block matches | `BlockStore.putIfAbsent` and ingest statistics | The browser estimates reusable logical bytes; the server reports reuse against its durable store. |

The browser does not fabricate throughput, compression, server health, physical allocation, or persistence. It demonstrates the content-addressing contract, not the durability path. For the complete operational path, run [Graviton locally](./guide/run-locally.md).

## Composition

The corresponding library concepts compose with sequential and fanout operators:

```scala
val ingest =
  IngestPipeline.countBytes >>>
  IngestPipeline.hashBytes() >>>
  IngestPipeline.rechunk(blockSize)

val verification =
  IngestPipeline.countBytes &&&
  IngestPipeline.hashBytes() &&&
  BlockVerify.verifier(expectedBlocks)
```

See [Transducer Algebra](./core/transducers.md) for the executable JVM implementation and tests.
