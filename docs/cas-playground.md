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
      // Navigate to the playground page after mount
      setTimeout(() => {
        if (window.location.hash !== '#/playground') {
          window.location.hash = '#/playground'
        }
      }, 100)
    }).catch(err => {
      console.warn('CAS Playground not loaded:', err.message);
      const appDiv = document.getElementById('graviton-app');
      if (appDiv) {
        appDiv.innerHTML = `
          <div style="padding: 2rem; text-align: center; background: rgba(0, 255, 65, 0.05); border: 1px solid rgba(0, 255, 65, 0.2); border-radius: 12px;">
            <h3 style="color: #00ff41;">🧪 CAS Playground</h3>
            <p>Build the Scala.js frontend to enable the interactive playground:</p>
            <pre style="background: rgba(0,0,0,0.3); padding: 1rem; border-radius: 8px; display: inline-block; text-align: left;"><code>./sbt buildFrontend
cd docs && npm run docs:dev</code></pre>
          </div>
        `;
      }
    });
  }
})
</script>

# CAS Playground

Experience content-addressed storage hands-on. Type text, paste content, or generate random data — watch Graviton's chunking, hashing, and deduplication algorithms work in real time.

::: tip How it works
The playground uses the **same algorithms** as the JVM-side `CasBlobStore`:
1. **Chunking** — splits input into fixed-size blocks (configurable 8–256 bytes for demo)
2. **Hashing** — computes a content digest for each block
3. **Deduplication** — tracks which block digests have been seen before
4. **Manifest** — shows how the blob maps to its constituent blocks
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
Input Bytes → Fixed-Size Chunker → Per-Block Hash → Dedup Check → Block Map
                  │                      │                │
                  ▼                      ▼                ▼
           Chunk[Byte]          BinaryKey.Block     Fresh | Duplicate
```

In the real Graviton runtime, this pipeline is composed using the Transducer algebra:

```scala
val ingest = BombGuard(maxBytes) >>> countBytes >>> hashBytes() >>> rechunk(blockSize) >>> blockKeyDeriver
```

Each `>>>` composes two stages sequentially, merging their summary Records automatically. The playground visualizes what each stage produces.
