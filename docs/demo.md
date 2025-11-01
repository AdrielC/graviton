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
Follow the [Scala.js Playbook](/dev/scalajs) for hot reload, bundling tips, and backend wiring while you iterate on this demo.
:::

# 🎮 Interactive Demo

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

- **🏠 Dashboard**: Overview of Graviton's capabilities
- **🔍 Blob Explorer**: Search and inspect blob metadata and manifests
- **📤 File Upload**: Interactive chunking visualization with multiple strategies
  - Compare Fixed-size vs FastCDC (content-defined) chunking
  - See block sharing and deduplication across files in real-time
  - Tune FastCDC bounds with the CAS Chunk Tuner to visualize breakpoints and explore dedup sensitivity
  - View validation results and chunk-level details
- **📊 Statistics**: Real-time system metrics and deduplication ratios

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
