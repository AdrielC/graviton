---
layout: doc
title: Pipeline Explorer
---

<script setup>
import PipelinePlayground from './.vitepress/theme/components/PipelinePlayground.vue'
</script>

# Pipeline Explorer

Graviton's `Transducer` algebra composes typed stream stages with `>>>` for sequential composition and `&&&` for fanout. The explorer renders a source-maintained catalog and a deterministic worksheet. It does not execute the JVM runtime and does not display runtime telemetry.

:::: warning Evidence boundary
`PipelineCatalog` lives in the cross-compiled shared module, but it is maintained by source rather than generated from running transducers. Implemented and descriptor-only stages are labeled separately. `CasBlobStore` uses the real chunker, block-key derivation, stores, manifest repository, and incremental blob hasher; it does not execute every catalog expression line for line.
::::

<PipelinePlayground />

## Worksheet rules

The Run Model control advances fixed rules that are stated in the interface:

- each model step adds 64 KiB
- a modeled block boundary occurs every 2 MiB
- when dedup is selected, every fourth modeled block is classified as a duplicate
- tick rate changes animation speed, not a claimed throughput
- digests, compression ratios, guard decisions, and verification results display `not computed`

These values explain how summary fields accumulate. They are not benchmark results or proof of stored data.

## Implemented foundation

The repository contains executable transducers for byte counting, incremental hashing, fixed-size rechunking, block-key derivation, generic deduplication, bomb guarding, and block verification. Their exact definitions and tests are linked from [Transducer Algebra](./core/transducers.md).

Compression remains a descriptor-only stage in the shared catalog. The explorer keeps it visible to explain intended composition, but labels it as not wired and refuses to invent a ratio.

## Composition and summaries

For implemented stages, sequential composition merges named summary fields:

```scala
val pipeline = countBytes >>> hashBytes() >>> rechunk(blockSize)

val (summary, blocks) = stream.run(pipeline.toSink)
summary.totalBytes
summary.digestHex
summary.blockCount
```

The catalog scenarios are teaching aids. For operational proof, run:

```bash
./scripts/demo-local.sh
TESTCONTAINERS=0 ./sbt test
```

## Learn more

- [Transducer Algebra](./core/transducers.md)
- [Binary Streaming](./guide/binary-streaming.md)
- [CAS Playground](./cas-playground.md)
- [Architecture](./architecture.md)
