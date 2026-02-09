---
layout: doc
title: Pipeline Explorer
---

<script setup>
import PipelinePlayground from './.vitepress/theme/components/PipelinePlayground.vue'
</script>

# Pipeline Explorer

Graviton's Transducer algebra lets you compose pipeline stages with `>>>` (sequential) and `&&&` (fanout). This interactive explorer shows how data flows through each stage, what summary fields are produced, and how composition works in practice.

::: tip How it works
Each box below is a **Transducer** — a typed, composable pipeline stage. Toggle stages on and off to see how the composition expression and summary record change. Hit **Run Pipeline** to watch animated data flow through.
:::

<PipelinePlayground />

## What you are seeing

### Composition Expression

The code shown above each pipeline is the **exact Scala expression** you would write to compose these transducers:

```scala
// Basic ingest
val pipeline = countBytes >>> hashBytes >>> rechunk(blockSize)

// Full CAS with dedup
val pipeline = countBytes >>> hashBytes >>> rechunk(blockSize) >>> blockKeyDeriver >>> dedup

// Verification with fanout
val check = countBytes &&& hashBytes &&& blockVerifier(manifest)
```

### Summary Record

When you compose transducers with `>>>`, their state types merge into a single `Record`:

```scala
val pipeline = countBytes >>> hashBytes >>> rechunk(blockSize)
// Record[("totalBytes" ~ Long) & ("digestHex" ~ String) & ("hashBytes" ~ Long) & ("blockCount" ~ Long) & ("rechunkFill" ~ Int)]

val (summary, blocks) = stream.run(pipeline.toSink)
summary.totalBytes   // Long — named field access, no casts
summary.digestHex    // String
summary.blockCount   // Long
```

### Data Packets

The animated particles represent different data types flowing through the pipeline:

| Packet | Meaning |
|--------|---------|
| **Raw Bytes** | Incoming `Chunk[Byte]` elements |
| **Block** | Rechunked `CanonicalBlock` after boundary detection |
| **Hash** | Content hash being computed (BLAKE3 / SHA-256) |
| **Manifest** | `ManifestEntry` emitted for the block manifest |

## Try the scenarios

| Scenario | Expression | Use Case |
|----------|-----------|----------|
| **Basic Ingest** | `countBytes >>> hashBytes >>> rechunk` | Minimum viable CAS ingest |
| **Full CAS Pipeline** | `count >>> hash >>> rechunk >>> key >>> dedup` | Production ingest with deduplication |
| **Safe Ingest** | `guard >>> count >>> hash >>> rechunk >>> compress` | Upload bomb protection + compression |
| **Verify + Hash** | `count &&& hash &&& verify` | Integrity check with fanout |

## Learn more

- **[Transducer Algebra](./core/transducers.md)** — Full documentation of the algebra, composition, and compilation targets
- **[Binary Streaming Guide](./guide/binary-streaming.md)** — How blocks, manifests, and chunkers fit together
- **[Architecture](./architecture.md)** — System-level module view
- **[Chunking Strategies](./ingest/chunking.md)** — FastCDC, fixed, delimiter, and anchored CDC
