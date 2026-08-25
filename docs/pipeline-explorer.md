---
layout: doc
title: Pipeline Explorer
---

# Pipeline Explorer

Graviton's ingest path turns bytes into bounded blocks, hashes those blocks, and writes a manifest that can reconstruct and verify the original blob. The interactive worksheet executes the in-memory portion locally through the compiled `graviton-shared` Scala.js module.

<PipelinePlayground />

## How this maps to the runtime

| Browser stage | Runtime counterpart | Boundary |
| --- | --- | --- |
| UTF-8 bytes | HTTP or CLI byte stream | The worksheet accepts at most 2,048 UTF-16 code units and refines the encoded payload to at most 8 KiB; the runtime accepts arbitrary binary streams. |
| Fixed chunking | `Chunker.fixed`, `Chunker.fastCdc`, or delimiter chunking | The shared lab intentionally uses 16 to 128-byte fixed boundaries for inspection. Runtime block bounds are separately configured. |
| SHA-256 | Incremental blob hasher and per-block key derivation | The shared analyzer uses Web Crypto on Scala.js and JCA on JVM. Server ingest remains incremental. |
| Repeated-block detection | `BlockStore.putIfAbsent` and ingest statistics | The worksheet checks repeats within one payload; the server deduplicates against its durable store. |

The browser does not fabricate throughput, compression, server health, or persistence. It demonstrates the content-addressing contract, not the durability path. For the complete operational path, run [Graviton locally](./guide/run-locally.md).

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
