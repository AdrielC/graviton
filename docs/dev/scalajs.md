# Scala.js Server Console Playbook

Graviton's Laminar frontend powers the **Connect Your Server** console in the documentation site. It calls a running Graviton server supplied by the visitor and shares response models with the JVM HTTP implementation. The published GitHub Pages site has no hosted backend and therefore starts disconnected.

## Project layout

- `modules/frontend/src/main/scala/graviton/frontend/` contains the console and HTTP client.
- `modules/protocol/graviton-shared` contains the cross-compiled response models.
- `docs/public/js/main.js` is the linked Graviton bundle written by `buildFrontend`.
- `modules/quasar-frontend` and `docs/public/quasar/js/main.js` are the separate Quasar UI surface.

## Development workflow

Start the server:

```bash
./sbt 'server/run'
```

Build the frontend and serve the docs in another terminal:

```bash
./sbt buildFrontend
npm ci --prefix docs
npm run docs:dev --prefix docs
```

Open `http://localhost:5173/demo`. The app defaults to `http://localhost:8081` and lets you persist a different endpoint from the connection bar. You can also use `?api=http://host:port` or a `graviton-api-url` meta element.

For repeated Scala edits, run `./sbt ~frontend/fastLinkJS`, then rerun `buildFrontend` when you want to refresh the VitePress asset directory.

## Request boundaries

- Upload sends the browser `File` directly as an `application/octet-stream` request body.
- Inventory and manifest views decode shared response models.
- Verify is a server operation that streams and hashes persisted bytes.
- Download uses authenticated Fetch against the raw blob route.
- Delete removes the manifest and refreshes durable inventory.

No request has a bundled fallback. A stopped server, CORS failure, malformed response, or storage error remains visible in the component that made the request.

## Quality gates

```bash
./sbt 'frontend/compile'
./sbt buildFrontend
./scripts/verify-http-lifecycle.sh
npm run docs:build --prefix docs
```

Before shipping, use browser developer tools to confirm that upload, inventory, metadata, verification, download, and deletion each issue the expected HTTP request. Stop the server once and verify that the UI shows a connection error rather than retaining success state.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| Console bundle unavailable | Run `./sbt buildFrontend` and refresh the page. |
| API request blocked by CORS | Use the default local security-disabled server, configure the console's exact origin in `GRAVITON_SECURITY_CORS_ALLOWED_ORIGINS`, or use a same-origin reverse proxy. |
| Wrong API endpoint | Change the connection bar or append `?api=http://host:port`. |
| Shared model type error | Run `./sbt clean frontend/compile` to force recompilation of cross-project sources. |

See the [frontend module reference](../modules/frontend.md) and [HTTP API](../api/http.md) for the full data contract.
