---
layout: doc
title: Connect Your Server
---

<script setup>
import { onMounted } from 'vue'

onMounted(() => {
  if (typeof window !== 'undefined' && typeof document !== 'undefined') {
    const rawBase = import.meta.env?.BASE_URL ?? '/'
    const normalizedBase = rawBase === '/' ? '' : rawBase.replace(/\/$/, '')
    window.__GRAVITON_DOCS_BASE__ = normalizedBase
    const jsPath = `${normalizedBase}/js/main.js`

    if (!document.getElementById('graviton-console-bundle')) {
      const script = document.createElement('script')
      script.id = 'graviton-console-bundle'
      script.type = 'module'
      script.src = jsPath
      script.onerror = error => {
        console.error('Graviton operations console failed to load:', error)
        const app = document.getElementById('graviton-app')
        if (app) {
          app.innerHTML = `
            <div class="error-message" style="padding: 2rem;">
              <h3>Operations console bundle is unavailable</h3>
              <p>Run <code>./sbt buildFrontend</code>, then rebuild or restart VitePress.</p>
            </div>
          `
        }
      }
      document.head.appendChild(script)
    }
  }
})
</script>

# Connect Your Server

This Scala.js application operates a Graviton server that you provide. GitHub Pages does not host a backend, so this page begins disconnected unless you start a server and configure its endpoint. It has no offline dataset, generated metrics, or substitute success state.

The console supports:

- streaming a selected file to `POST /api/blobs`
- listing persisted manifests from `GET /api/blobs`
- inspecting the exact block layout from `GET /api/blobs/{id}/metadata`
- reading and hashing stored bytes through `POST /api/blobs/{id}/verify`
- downloading the stored bytes
- deleting a blob manifest while retaining shared CAS blocks
- reading live health and process counters

## Start the service

```bash
GRAVITON_BLOB_BACKEND=fs \
GRAVITON_FS_ROOT=/tmp/graviton-data \
GRAVITON_HTTP_PORT=8081 \
./sbt "server/run"
```

The default unsecured local server enables CORS so a locally served documentation console can call it. Security-enabled deployments must terminate the UI and API under an intentional origin policy.

Build and serve the console in another terminal:

```bash
./sbt buildFrontend
npm ci --prefix docs
npm run docs:dev --prefix docs
```

Use the API endpoint field below to connect. The selected endpoint is stored in browser local storage. Connection failures remain visible and do not trigger a fallback. For a self-contained public interaction, use the [CAS Playground](./cas-playground.md).

<meta name="graviton-api-url" content="http://localhost:8081" />

<div id="graviton-app"></div>

## Verify without a browser

```bash
printf 'hello graviton\n' > /tmp/graviton-input.txt

BLOB_ID="$(
  curl -fsS -X POST \
    -H 'content-type: application/octet-stream' \
    --data-binary @/tmp/graviton-input.txt \
    http://localhost:8081/api/blobs | jq -r '.blob.id'
)"

curl -fsS http://localhost:8081/api/blobs | jq .
curl -fsS "http://localhost:8081/api/blobs/$BLOB_ID/metadata" | jq .
curl -fsS -X POST "http://localhost:8081/api/blobs/$BLOB_ID/verify" | jq .
curl -fsS "http://localhost:8081/api/blobs/$BLOB_ID" -o /tmp/graviton-output.txt
cmp /tmp/graviton-input.txt /tmp/graviton-output.txt
```
