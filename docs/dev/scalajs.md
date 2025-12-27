# Scala.js Playbook

Graviton's interactive surfaces ride on the cross-compiled Scala.js frontend that powers the documentation demo. This guide walks through the daily workflow, hot-reload tricks, linting, and production bundling so you can ship new client features with confidence.

:::tip TL;DR
1. `./sbt ~frontend/fastLinkJS` for instant Laminar rebuilds.
2. In another terminal: `cd docs && npm run docs:dev` to mount the demo inside VitePress.
3. Point the app at a backend with `<meta name="graviton-api-url" content="http://localhost:8081" />` or let demo data take over offline.
:::

## Project Layout

- `modules/frontend/src/main/scala/graviton/frontend/` - Laminar components, routing, shared UI models.
- `modules/frontend/src/main/scala/graviton/frontend/Main.scala` - Scala.js entry point; bridges the bundle to `#graviton-app` on the docs page.
- `modules/quasar-frontend/src/main/scala/quasar/frontend/` - Quasar Laminar app (tenant-implicit UI surface, starting with health + legacy import).
- `modules/protocol/graviton-shared` - JVM/JS shared data types (compiled twice by sbt cross projects).
- `docs/public/js/main.js` - Final bundle shipped with the docs (written by `buildFrontend`).
- `docs/public/quasar/js/main.js` - Quasar demo bundle shipped with the docs (written by `buildQuasarFrontend`).

```bash
tree modules/frontend/src/main/scala/graviton/frontend -L 1
```

## Dev Workflow

### 1. Start an incremental Scala.js build

```bash
./sbt ~frontend/fastLinkJS
```

- Recompiles the Laminar app on every file save.
- Outputs the bundle to `modules/frontend/target/.../fastopt` and leaves source maps intact for browser debugging.

### 2. Serve the documentation shell

```bash
cd docs
npm install  # first time only
npm run docs:dev
```

- VitePress serves the docs on `http://localhost:5173` (default).
- The `/demo` route dynamically imports `/js/main.js` from `docs/public/js/`. For the simplest workflow, prefer `./sbt buildFrontend` to refresh `docs/public/js/`.

The Quasar demo route (`/quasar-demo`) dynamically imports `/quasar/js/main.js`. Use `./sbt buildQuasarFrontend` (or `./sbt buildDocsAssets`) to refresh it.

> For one-off previews, run `./sbt buildFrontend` instead; it writes the release bundle for you.

### 3. (Optional) Point at a local backend

```html
<!-- docs/demo.md -->
<meta name="graviton-api-url" content="http://localhost:8081" />
```

When the API cannot be reached the UI falls back to simulated demo data, so you can develop offline and still showcase features.

## Quality Gates

- **Static analysis**: The usual repo-wide `TESTCONTAINERS=0 ./sbt scalafmtAll test` already formats and tests the Scala.js cross projects. Run it before committing doc or frontend changes.
- **Browser diagnostics**: Laminar renders meaningful console warnings. Keep DevTools open and enable "Preserve log" when hot reloading.
- **Type sharing**: Add new protocol models in `modules/protocol/graviton-shared`. sbt will compile them for both JVM and JS - avoid duplicating DTOs in the frontend.

## Shipping a New Build

1. `./sbt buildFrontend` (produces an optimized `docs/public/js/main.js`).
2. `cd docs && npm run docs:build` (bundles static assets for deployment).
3. Commit both the docs markdown changes and the generated bundle.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `Interactive demo not available` banner | Run `./sbt buildFrontend` or refresh the symlink to the `fastopt` bundle. |
| API calls fail with CORS | Launch the server locally (`./sbt "server/run"`) or set up a proxy in `docs/vite.config.ts`. |
| Hot reload lags | Switch to `~frontend/fastLinkJS` instead of `fullLinkJS`. The latter performs full DCE and is slower. |
| Type errors in shared models | Run `./sbt clean frontend/compile` to force recompilation of shared sources. |

## Next Steps

- Extend `modules/frontend` with new Laminar components (see the refreshed [Scala.js Frontend module doc](../modules/frontend.md)).
- Explore the [Interactive Demo](../demo.md) for layout inspiration - its CSS now lives in `docs/.vitepress/theme/custom.css` for reuse.
- Wire live metrics into the docs via the Vue components under `.vitepress/theme/components/`.
- Use the Schema Explorer route (`#/schema`) to validate shared models and view sample payloads served entirely from Scala.js + ZIO.
