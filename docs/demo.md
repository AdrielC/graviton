---
layout: doc
title: Scala.js Dashboard
---

::: tip Prefer the hosted playground?
The **[CAS Playground](./cas-playground.md)** works on GitHub Pages with no Scala.js build. This page is the optional **Scala.js dashboard** (upload UI, explorer) for local docs or when you run `./sbt buildFrontend`.
:::

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

# Scala.js Dashboard

Explore Graviton's models and browser-side tools through this Scala.js application. For a lightweight, always-available CAS demonstration, use the [CAS Playground](./cas-playground.md) instead.

::: info Build Checklist
1. Run `./sbt buildFrontend` from the repo root to refresh `docs/public/js/main.js`.
2. Rebuild the docs (`npm run docs:dev` or `npm run docs:build`).
3. Reload this page. Navigation should stay in-app (no full page flashes), and browser devtools should show the `main.js` chunk loading from `/js/` or your configured base path.
:::

::: info Implementation Note
The file analyzer uses the same FastCDC implementation as `graviton-streams`, compiled to JavaScript. Selected files remain in the browser; this view does not send them to the server.
:::

<meta name="graviton-api-url" content="http://localhost:8081" />

<div id="graviton-app"></div>

::: tip Note
By default the demo looks for a Graviton instance at `http://localhost:8081`. Update the `<meta name="graviton-api-url" />` tag if your server runs elsewhere (or if you changed `GRAVITON_HTTP_PORT`).
:::

::: info Demo Mode
When this page cannot reach a live server, such as on GitHub Pages, the UI switches to a reference dataset and displays a persistent demo-mode banner. Reference metadata and stats are not live telemetry.
:::

## Features

The embedded UI is intended to demonstrate the pieces that exist in this repo today:

- **Local file analysis** using the shared FastCDC implementation
- **Reference blob and manifest inspection** with explicit offline labeling
- **Process ingest counters** when connected to the server
- **Source-backed capability dashboard** and schema-derived explorer
- **Capacity Lab** using editable assumptions and runnable CLI commands

## Try it yourself

1. **Start a Graviton server**:
   ```bash
   ./sbt "server/run"
   ```

2. **Upload a blob and capture its content ID**:

   ```bash
   BLOB_ID="$(
     curl -fsS -X POST --data-binary @/path/to/file \
       http://localhost:8081/api/blobs | jq -r .
   )"
   ```

3. **Retrieve** it with `curl http://localhost:8081/api/blobs/$BLOB_ID --output retrieved.bin`.

The current browser explorer is a reference-data teaching surface; it does not parse the raw blob response into metadata. Use the HTTP API directly for the live blob lifecycle.

## Architecture

This frontend is built with:

- **Scala.js**: Type-safe JavaScript from Scala
- **Laminar**: Reactive UI with FRP (Functional Reactive Programming)
- **ZIO**: Effect system for async operations
- **Shared Models**: Cross-compiled protocol models between JVM and JS
