# Graviton Frontend

The frontend is a Scala.js and Laminar application embedded in the VitePress documentation site. It combines API-backed status views with clearly labeled browser-only learning tools.

## Current views

| View | Data boundary |
| --- | --- |
| Dashboard | Static capability overview |
| Blob Explorer | Bundled reference metadata with structurally valid SHA-256 content IDs |
| Analyze | Local browser file analysis; selected bytes are not uploaded |
| Stats | `/api/stats` process counters with a labeled reference fallback |
| Schema | `/api/schema` or the bundled reference catalog when offline |
| Updates | Source-backed dashboard snapshot plus optional SSE updates |
| Pipeline | Explicit visualization of implemented and roadmap stages |
| CAS Lab | Browser-side CAS simulation using real SHA-256 |
| Capacity Lab | Deterministic storage arithmetic from editable assumptions |

The raw blob API returns bytes from `GET /api/blobs/:id`. The browser Blob Explorer does not pretend that response is a JSON metadata endpoint. Use the [HTTP guide](../../docs/api/http.md) for the live blob lifecycle.

## Build

From the repository root:

```bash
./sbt 'frontend/compile'
./sbt buildFrontend
```

`buildFrontend` links the Scala.js application and copies it into `docs/public/js/`. `./sbt buildDocsAssets` also builds the Quasar bundle and Scaladoc.

Build the complete site with the locked Node dependency graph:

```bash
npm ci --prefix docs
npm run docs:build --prefix docs
```

## Local development

```bash
./sbt buildFrontend
npm ci --prefix docs
npm run docs:dev --prefix docs
```

Open `http://localhost:5173/demo`. The page currently sets the API URL to `http://localhost:8081` through:

```html
<meta name="graviton-api-url" content="http://localhost:8081" />
```

When the API is unavailable, API-backed views switch to reference data and the application displays a persistent demo-mode banner.

## Structure

```text
modules/frontend/src/main/scala/graviton/frontend/
├── BrowserHttpClient.scala
├── DemoDataset.scala
├── GravitonApi.scala
├── GravitonApp.scala
├── Main.scala
└── components/
```

Shared JSON and dashboard models live in `modules/protocol/graviton-shared`. Styles live in `docs/.vitepress/theme/custom.css`.

## Validation

```bash
TESTCONTAINERS=0 ./sbt scalafmtAll test
./sbt buildDocsAssets
npm ci --prefix docs
npm run docs:build --prefix docs
```

For visual QA, inspect at least the dashboard, CAS Lab, Capacity Lab, and a narrow mobile viewport. Confirm the demo-mode banner appears when no API is running and that the browser console has no uncaught errors.
