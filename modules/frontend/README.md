# Graviton Frontend

The frontend is a Scala.js and Laminar operations console embedded in the VitePress documentation site. Every value in the console comes from the configured Graviton HTTP server. Connection failures remain visible and are never replaced with bundled data.

## Operational views

| View | Server-backed behavior |
| --- | --- |
| Operations | Health, durable inventory totals, process counters, and direct workflow links |
| Upload | Sends the selected file bytes to `POST /api/v1/blobs` and displays the committed result |
| Inventory | Lists persisted manifests, inspects real block layouts, verifies bytes, downloads blobs, and deletes manifests |
| Counters | Displays the current server process counters with their reset boundary |

The console accepts an API endpoint in the connection bar. It stores the endpoint in browser local storage and reloads against that server.

## Build

From the repository root:

```bash
./sbt 'frontend/compile'
./sbt buildFrontend
```

`buildFrontend` links the Scala.js application and copies it into `docs/public/js/`. `./sbt buildDocsAssets` also builds the shared content lab and Scaladoc.

Build the complete site with the locked Node dependency graph:

```bash
npm ci --prefix docs
npm run docs:build --prefix docs
```

## Local operation

Run the backend and docs server in separate terminals:

```bash
./sbt 'server/run'
```

```bash
./sbt buildFrontend
npm ci --prefix docs
npm run docs:dev --prefix docs
```

Open `http://localhost:5173/demo`. The endpoint is resolved in this order:

1. `?api=http://host:port`
2. the endpoint saved by the connection bar
3. `<meta name="graviton-api-url">`
4. `http://localhost:8081`

## Structure

```text
modules/frontend/src/main/scala/graviton/frontend/
├── BrowserHttpClient.scala
├── GravitonApi.scala
├── GravitonApp.scala
├── Main.scala
└── components/
    ├── BlobExplorer.scala
    ├── FileUpload.scala
    ├── HealthCheck.scala
    └── StatsPanel.scala
```

Shared response models live in `modules/protocol/graviton-shared`. Styles live in `docs/.vitepress/theme/custom.css`.

## Validation

```bash
TESTCONTAINERS=0 ./sbt scalafmtAll test
./sbt buildDocsAssets
npm ci --prefix docs
npm run docs:build --prefix docs
```

For runtime and visual QA, start the server, run `./scripts/verify-http-lifecycle.sh`, and inspect upload, inventory, manifest, verification, retrieval, and deletion in the browser at desktop and mobile widths.
