---
layout: doc
title: Interactive Demo
---

<script setup>
import { onMounted } from 'vue'

onMounted(() => {
  // Only run in browser, not during SSR
  if (typeof window !== 'undefined' && typeof document !== 'undefined') {
    const rawBase = import.meta.env?.BASE_URL ?? '/'
    const normalizedBase = rawBase === '/' ? '' : rawBase.replace(/\/$/, '')
    if (typeof window !== 'undefined') {
      window.__GRAVITON_DOCS_BASE__ = normalizedBase
    }
    const jsPath = `${normalizedBase}/js/main.js`

    // Dynamically import the main module
    import(jsPath).catch(err => {
      console.warn('Interactive demo not loaded:', err.message);
      const appDiv = document.getElementById('graviton-app');
      if (appDiv) {
        appDiv.innerHTML = `
          <div class="error-message" style="padding: 2rem; text-align: center; background: rgba(255, 200, 0, 0.1); border: 1px solid rgba(255, 200, 0, 0.3); border-radius: 12px;">
            <h3>⚠️ Interactive Demo Not Available</h3>
            <p>The Scala.js frontend hasn't been built yet. To enable the interactive demo:</p>
            <ol style="text-align: left; display: inline-block; margin: 1rem auto;">
              <li>Build the frontend: <code style="background: rgba(0,0,0,0.1); padding: 0.2rem 0.4rem; border-radius: 4px;">./sbt buildFrontend</code></li>
              <li>Rebuild the docs: <code style="background: rgba(0,0,0,0.1); padding: 0.2rem 0.4rem; border-radius: 4px;">cd docs && npm run docs:build</code></li>
            </ol>
            <p style="margin-top: 1rem;">
              <small>For development: run <code style="background: rgba(0,0,0,0.1); padding: 0.2rem 0.4rem; border-radius: 4px;">./sbt buildFrontend</code> then <code style="background: rgba(0,0,0,0.1); padding: 0.2rem 0.4rem; border-radius: 4px;">npm run docs:dev</code></small>
            </p>
          </div>
        `;
      }
    });
  }
})
</script>

<!-- Demo styles provided via docs/.vitepress/theme/custom.css -->

:::tip Scala.js Loop
Follow the [Scala.js Playbook](./dev/scalajs.md) for hot reload, bundling tips, and backend wiring while you iterate on this demo.
:::

# Interactive Demo

Experience Graviton's capabilities through this interactive Scala.js application!

::: info Build Checklist
1. Run `./sbt buildFrontend` from the repo root to refresh `docs/public/js/main.js`.
2. Rebuild the docs (`npm run docs:dev` or `npm run docs:build`).
3. Reload this page—navigation should stay in-app (no full page flashes) and browser devtools should show the `main.js` chunk loading from `/js/` or your configured base path.
:::

::: info Implementation Note
The chunking algorithms demonstrated here use the **same FastCDC implementation** as the server-side code in `graviton-streams`. Upload multiple files to see real content-defined chunking and block-level deduplication in action!
:::

<meta name="graviton-api-url" content="http://localhost:8080" />

<div id="graviton-app"></div>

::: tip Note
By default the demo looks for a Graviton instance at `http://localhost:8080`. Update the `<meta name="graviton-api-url" />` tag if your server runs elsewhere.
:::

::: info Demo Mode
When this page cannot reach a live server (such as on GitHub Pages), the UI automatically switches to a simulated dataset. You can still explore chunking, manifests, and stats without any backend.
:::

## Features

This interactive demo showcases:

- **Mission Control Studio**: Compose ingest missions by picking scenarios, tuning chunkers, and exporting ready-to-run configs complete with CLI checklists and live telemetry.
  - Scenario presets for observatories, genomics, and edge observability workloads
  - Live metric projections (throughput, dedup savings, durability, carbon impact)
  - Auto-generated HOCON configuration + CLI launch steps tied to your toggles
  - Narrative event stream with pause/inject controls so you can demo outcomes on stage
- **Dashboard**: Overview of Graviton's capabilities
- **Blob Explorer**: Search and inspect blob metadata and manifests
- **File Upload**: Interactive chunking visualization with multiple strategies
  - Compare Fixed-size vs FastCDC (content-defined) chunking
  - See block sharing and deduplication across files in real-time
  - Tune FastCDC bounds with the CAS Chunk Tuner to visualize breakpoints and explore dedup sensitivity
  - View validation results and chunk-level details
- **Schema Explorer**: Browse the shared API models, drill into field definitions, and view sample JSON powered by Scala.js and ZIO running in the browser
- **Statistics**: Real-time system metrics and deduplication ratios

## Next Wave: Interactive Labs

We are expanding the experience with a slate of Scala.js sandboxes that plug straight into the existing Laminar shell. Each one runs fully client-side with Web Workers so you can benchmark and visualize without leaving the browser.

### Inline BlobStore Benchmark

- Benchmark BLAKE3 vs SHA-256 against the same file, streaming bytes through Workers to keep the UI responsive.
- Toggle chunking modes (FastCDC, fixed, none) and see how hash cost and dedup savings shift.
- Flip between IndexedDB (local) and remote MinIO URLs; perf charts show network + hashing overheads.
- Flame-graph timeline overlay makes “hash heat” tangible: hover to see per-stage latency and throughput.

### CAS Mental Model Simulator

- Drag a file into the canvas and watch the hash ladder light up as blocks are chunked and hashed.
- Drop in a second file and see identical blocks collapse into the same `FileKey`; collisions highlight why deterministic chunking matters.
- Edit a byte: only the impacted blocks rehash, the manifest diff animates, and users feel the “content address” concept immediately.

### S3 / Ceph Translator Map

- Pick any `BlobKey` from the explorer; the panel fans out how that blob is expressed on each backend (S3 path, MinIO layout, Ceph RADOS object, filesystem path, Postgres chunk rows).
- Shows “Graviton doesn’t care” in one glance: every backend tile stays in sync as you scrub through replicas.

### Block Repair / Rehydration Demo

- Simulate missing blocks in the primary store; backup replicas stream in and the manifest indicator ticks back to healthy.
- Chaos toggle randomly removes blocks so you can watch Graviton rehydrate from surviving sectors and rebuild the `FileKey`.
- Ideal for teaching operators why replication policies and background repair jobs matter.

### ZIP Virtualization Explorer

- Upload a ZIP, then split view shows “opaque blob” vs “virtual directory” modes.
- Step through re-zip strategies (sorted entries, normalized metadata, canonical timestamps) and see how hashes shift.
- Explain the canonical hashing problem visually, including knobs for metadata stripping and recompression.

### End-to-end Ingestion Pipeline Simulator

- Animates the full ingest lifecycle: browser streaming → hashing → manifest assembly → KMS encrypt → `BlockStore` writes → CAS identity → metadata commits → outbox/finalization.
- Each stage is backed by the actual pipeline graph, so pausing at any node reveals the ZIO service stack and relevant configuration handles.

## Launch Mission Control Studio

1. Click the `🛠️ Mission Control` tab inside the embedded app navigation (or jump straight there with `#/mission`).
2. Pick a scenario pill (observatory firehose, genomics quorum, or observability edge mesh) to load scale, compliance, and region presets.
3. Use the chunker chips, range sliders, and toggles to explore how FastCDC vs Anchored CDC vs fixed windows change projected ingest rates, dedup savings, and durability.
4. Scroll to **Deployment kit** to copy the generated HOCON plus CLI checklist—each control you tweak rewrites the config instantaneously.
5. Keep the **Live mission feed** running (or inject your own insight) to narrate how the runtime reacts. Pause/resume the ticker mid-demo to highlight specific pipeline moments.

## Try it yourself

1. **Start a Graviton server**:
   ```bash
   sbt "server/run"
   ```

2. **Upload some blobs** using the API or CLI

3. **Explore** the blobs using the interactive UI above!

## Architecture

This frontend is built with:

- **Scala.js**: Type-safe JavaScript from Scala
- **Laminar**: Reactive UI with FRP (Functional Reactive Programming)
- **ZIO**: Effect system for async operations
- **Shared Models**: Cross-compiled protocol models between JVM and JS
