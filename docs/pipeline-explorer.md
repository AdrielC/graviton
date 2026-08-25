---
layout: doc
title: Pipeline Explorer
---

# Pipeline Explorer

Graviton's ingest path turns bytes into bounded blocks, hashes those blocks, and writes a manifest that can reconstruct and verify the original blob. The interactive worksheet executes the first three transformations locally over your exact input.

<PipelinePlayground />

## How this maps to the runtime

| Browser stage | Runtime counterpart | Boundary |
| --- | --- | --- |
| UTF-8 bytes | HTTP or CLI byte stream | The worksheet starts from text; the runtime accepts arbitrary binary bytes. |
| Fixed chunking | `Chunker.fixed`, `Chunker.fastCdc`, or delimiter chunking | The worksheet intentionally uses fixed boundaries. |
| SHA-256 | Incremental blob hasher and per-block key derivation | The digest and content-ID format are real. |
| Repeated-block detection | `BlockStore.putIfAbsent` and ingest statistics | The worksheet checks repeats within one payload; the server deduplicates against its durable store. |

The browser does not fabricate throughput, compression, server health, or persistence. For the complete operational path, run [Graviton locally](./guide/run-locally.md).

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
