---
layout: doc
title: CAS Playground
---

<script setup>
import { onMounted } from 'vue'

onMounted(() => {
  if (typeof window !== 'undefined' && typeof document !== 'undefined') {
    const rawBase = import.meta.env?.BASE_URL ?? '/'
    const normalizedBase = rawBase === '/' ? '' : rawBase.replace(/\/$/, '')
    if (typeof window !== 'undefined') {
      window.__GRAVITON_DOCS_BASE__ = normalizedBase
    }
    const jsPath = `${normalizedBase}/js/main.js`
    import(jsPath).then(() => {
      setTimeout(() => {
        if (window.location.hash !== '#/playground') {
          window.location.hash = '#/playground'
        }
      }, 100)
    }).catch(err => {
      console.warn('CAS Playground not loaded:', err.message)
    })
  }
})
</script>

# CAS Playground

Experience content-addressed storage hands-on. Type text, paste content, or generate random data — watch Graviton's chunking, hashing, and deduplication algorithms work in real time.

::: tip How it works
The playground uses the **same algorithms** as the JVM-side `CasBlobStore`:
1. **Chunking** — splits input into fixed-size blocks (configurable 8–256 bytes for demo)
2. **Hashing** — computes a real SHA-256 digest for each block via `pt.kcry:sha`
3. **Deduplication** — tracks which block digests have been seen before
4. **Iron types** — `BlockSize`, `Sha256Hex`, `BlockIndex` enforce invariants at the type level
:::

::: info Build Checklist
1. Run `./sbt buildFrontend` from the repo root.
2. Run `cd docs && npm run docs:dev`.
3. Navigate to this page.
:::

<meta name="graviton-api-url" content="http://localhost:8081" />

<div id="graviton-app"></div>

## Try These Experiments

### 1. Duplicate Detection
1. Type "hello world" and click **Ingest**
2. Type "hello world" again and click **Ingest** again
3. Watch the second ingest show **100% dedup ratio** — all blocks are duplicates!

### 2. Partial Overlap
1. Type "AAAA BBBB CCCC DDDD" with block size 4
2. Then type "AAAA BBBB XXXX DDDD"
3. See which blocks are fresh (changed) and which are deduplicated (unchanged)

### 3. Block Size Impact
1. Enter a paragraph of text
2. Try different block sizes (8, 32, 64, 128, 256)
3. Notice how smaller blocks find more dedup opportunities but create more metadata

### 4. Random Data
1. Switch to **Random Data** mode
2. Generate 512 bytes and ingest
3. Generate another 512 bytes — since random data rarely repeats, expect 0% dedup

## Architecture

The CAS Playground mirrors the production pipeline:

```
Input Bytes → Fixed-Size Chunker → Per-Block SHA-256 → Dedup Check → Block Map
                  │                      │                │
                  ▼                      ▼                ▼
           Chunk[Byte]            Sha256Hex           Fresh | Duplicate
```

In the real Graviton runtime, this pipeline is composed using the Transducer algebra:

```scala
val ingest = BombGuard(maxBytes) >>> countBytes >>> hashBytes() >>> rechunk(blockSize) >>> blockKeyDeriver
```

Each `>>>` composes two stages sequentially, merging their summary Records automatically. The playground visualizes what each stage produces.

## Iron Types

All types in the playground are cross-compiled Iron refined types from `graviton.shared.cas`:

| Type | Constraint |
|------|-----------|
| `BlockSize` | `Int :| GreaterEqual[1] & LessEqual[16777216]` |
| `BlockIndex` | `Int :| GreaterEqual[0]` |
| `ByteCount` | `Long :| GreaterEqual[0L]` |
| `Sha256Hex` | `String :| Match["[0-9a-f]{64}"]` |
| `HexDigest` | `String :| Match["[0-9a-f]+"] & MinLength[1] & MaxLength[128]` |
| `Algo` | `String :| Match["(sha-256\|sha-1\|blake3\|md5)"]` |
