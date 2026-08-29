# Scala.js Browser Surfaces

Graviton ships two honest Scala.js surfaces. The Laminar **Connect Your Server** console calls a running server supplied by the visitor. The CAS Playground links the bounded `graviton-shared` content-addressing analyzer and computes locally without claiming persistence. The published GitHub Pages site has no hosted backend, so only the local lab works without an operator-supplied endpoint.

## Project layout

- `modules/frontend/src/main/scala/graviton/frontend/` contains the console and HTTP client.
- `modules/protocol/graviton-shared/shared` contains common response models, refined CAS types, and analysis logic.
- `modules/protocol/graviton-shared/jvm` uses JCA SHA-256.
- `modules/protocol/graviton-shared/js` uses Web Crypto and exports `analyzeGravitonContent`.
- `docs/public/content-lab/main.js` is the linked shared library written by `buildContentLab`.
- `docs/public/js/main.js` is the linked Graviton bundle written by `buildFrontend`.

The repository retains an unpublished, source-only internal document-layer prototype. It is not linked into the public Graviton documentation build.

## Development workflow

Start the server:

```bash
./sbt 'server/run'
```

Build the frontend and serve the docs in another terminal:

```bash
./sbt buildContentLab buildFrontend
npm ci --prefix docs
npm run docs:dev --prefix docs
```

Open `http://localhost:5173/demo`. The app defaults to `http://localhost:8081` and lets you persist a different endpoint from the connection bar. You can also use `?api=http://host:port` or a `graviton-api-url` meta element.

For repeated console edits, run `./sbt ~frontend/fastLinkJS`. For shared CAS edits, run `./sbt ~sharedProtocolJS/fastLinkJS`. Rerun the corresponding copy task before testing through VitePress.

## Request boundaries

- Upload sends the browser `File` directly as an `application/octet-stream` request body.
- Inventory and manifest views decode shared response models.
- Verify is a server operation that streams and hashes persisted bytes.
- Download uses authenticated Fetch against the raw blob route.
- Delete removes the manifest and refreshes durable inventory.
- The CAS Playground accepts text only, checks a 2,048-code-unit limit before UTF-8 encoding, refines the result to at most 8 KiB, and calls the exported shared analyzer.

No request has a bundled fallback. A stopped server, CORS failure, malformed response, or storage error remains visible in the component that made the request.

## Quality gates

```bash
./sbt 'frontend/compile'
./sbt 'sharedProtocolJVM/test' 'sharedProtocolJS/test'
./sbt buildContentLab buildFrontend
./scripts/verify-http-lifecycle.sh
npm run docs:build --prefix docs
```

Before shipping, use browser developer tools to confirm that upload, inventory, metadata, verification, download, and deletion each issue the expected HTTP request. Stop the server once and verify that the UI shows a connection error rather than retaining success state.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| Console bundle unavailable | Run `./sbt buildFrontend` and refresh the page. |
| CAS Playground says Scala.js unavailable | Run `./sbt buildContentLab` and refresh the page. |
| API request blocked by CORS | Use the default local security-disabled server, configure the console's exact origin in `GRAVITON_SECURITY_CORS_ALLOWED_ORIGINS`, or use a same-origin reverse proxy. |
| Wrong API endpoint | Change the connection bar or append `?api=http://host:port`. |
| Shared model or analyzer type error | Run `./sbt clean sharedProtocolJVM/compile sharedProtocolJS/compile frontend/compile` to force recompilation of cross-project sources. |

See the [frontend module reference](../modules/frontend.md) and [HTTP API](../api/http.md) for the full data contract.
