# Scala.js Frontend

`modules/frontend` hosts the Laminar-powered dashboard that ships with the documentation site. A custom sbt task (`buildFrontend`) compiles the Scala.js sources and copies the resulting bundle to `docs/public/js/main.js`.

## Build workflow

```
sbt buildFrontend

cd docs
npm run docs:dev
```

- The bundle is imported dynamically by `docs/demo.md`, so the same assets serve local dev (`npm run docs:dev`) and the GitHub Pages deployment.
- CI already invokes `sbt buildFrontend`; run it locally after editing Scala sources or adding dependencies.

## Application entry points

- `Main` reads the `<meta name="graviton-api-url" />` tag and mounts the Laminar tree under `#graviton-app` once the DOM is ready.
- `GravitonApp` orchestrates routing, header/footer layout, and renders the Dashboard, Explorer, Upload, and Stats pages.
- Navigation uses `waypoint` with hash-based routes so deep links work on GitHub Pages without server configuration.

## API client & demo mode

- `GravitonApi` wraps the shared `HttpClient` trait and exposes an `offlineSignal` Laminar `Signal[Boolean]` to toggle demo messaging.
- `DemoData` contains canned health, stats, and manifest responses. When real HTTP calls fail, `GravitonApi` falls back to this dataset and logs a warning to the browser console.
- `BrowserHttpClient` executes Fetch requests; the combination ensures the UI behaves even without a live Graviton instance.

## Components

- **HealthCheck**: polls `/api/health`, displays status badges, and surfaces demo mode notifications.
- **BlobExplorer**: loads metadata/manifests and, in demo mode, offers pre-populated blob IDs for quick inspection.
- **FileUpload**: pure client-side chunking visualiser supporting fixed-size and FastCDC placeholder strategies. Reports deduplication metrics, highlights shared chunks, and includes a CAS Chunk Tuner to experiment with FastCDC min/avg/max bounds.
- **StatsPanel**: fetches `/api/stats`, auto-loads once on mount, and renders aggregate counters.

Each component returns a pure Laminar `HtmlElement`, which keeps them easy to test and compose.

## Extending the frontend

1. Create new components under `modules/frontend/src/main/scala/graviton/frontend/components/`.
2. Wire them into `GravitonApp` (navigation + page rendering) and expose additional routes if needed.
3. Update `docs/demo.md` or `docs/.vitepress/theme/custom.css` when adding new CSS classes.
4. Re-run `sbt buildFrontend` and rebuild the docs to refresh the bundled assets.

The module purposefully avoids framework globals; everything flows through Laminar signals and ZIO effects, making it straightforward to reuse pieces across future UIs.
