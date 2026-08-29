# Scala.js Browser Surfaces

Graviton ships three distinct Scala.js browser surfaces. They share types where that prevents protocol drift, but they do not pretend to be one runtime.

| Surface | Module | Boundary |
| --- | --- | --- |
| Operations console | `modules/frontend` | Calls a real Graviton HTTP server selected by the operator |
| Streamed file analyzer | `modules/frontend/graviton-content-lab` | Reads local browser files, computes chunk maps and reuse, never persists |
| PDF editor | `modules/frontend/graviton-pdf-lab` | Inspects PDFs and performs explicitly bounded document rewrites |

The published GitHub Pages site has no hosted Graviton backend. The CAS Playground therefore proves local content analysis, while the operations console requires an operator-supplied server.

## Build outputs

- `./sbt buildFrontend` writes the Laminar operations console to `docs/public/js/`.
- `./sbt buildContentLab` full-links the file analyzer into `docs/.vitepress/generated/content-lab/` and the PDF editor into `docs/.vitepress/generated/pdf-lab/`.
- Vite bundles the analyzer as the playground's first dynamic dependency. The larger document graph editor is imported only after bytes confirm a PDF.

Generated Vite inputs are ignored by Git. CI and a clean local build must run the sbt asset task before `npm run docs:build`.

## Development workflow

```bash
npm ci --prefix docs
./sbt buildContentLab buildFrontend
npm run docs:dev --prefix docs
```

Open `http://localhost:5173/cas-playground` for local file comparison. Start `./sbt 'server/run'` and open `http://localhost:5173/demo` for the HTTP-backed operations console.

## Byte ownership

The analyzer does not call `File.arrayBuffer()` and does not collect a complete upload. `BrowserBlobSource` advances through the browser-owned file with explicit 64 KiB `Blob.slice()` requests. Each bounded slice is copied into a ZIO chunk, then the byte stream is broadcast to an incremental whole-file digest and a chunker with a lag of two bytes per subscriber.

The exported working-byte ceiling includes:

- the five-byte PDF signature;
- one 64 KiB browser slice and one 64 KiB digest window;
- the two broadcast queues at their configured two-byte lag;
- the PDF scanner carry, capped at the smaller of the target and 1 MiB;
- the chunker's 4 MiB backing buffer and at most one emitted Iron-refined block from 1 byte through 4 MiB; and
- the final block metadata, capped at 20,000 entries; and
- the constant-size incremental SHA-256 state.

The ceiling excludes the browser-owned input `Blob`, JavaScript module code, Vue state, and the capped metadata objects because those are not file-byte buffers. `BrowserFileAnalysisSpec` verifies exact identity, reusable source opening, manifest overflow, and release of an active source when its analysis fiber is interrupted.

Font inventory uses ZIO PDF's separately scoped `Blob.stream()` source and does not collect the complete file. Font replacement is different: the current ZIO PDF transform needs a decoded document graph, so the browser editor accepts at most 32 MiB and caps each canonical or edited output at 32 MiB. The output collector reads at most that limit plus one byte before refinement.

## Request boundaries

- Console upload sends the browser `File` directly as an `application/octet-stream` request body.
- Inventory and manifest views decode shared response models.
- Verify streams and hashes persisted bytes on the server.
- Download uses authenticated Fetch against the raw blob route.
- Delete removes the manifest and refreshes durable inventory.
- The CAS Playground uses byte sniffing for PDF selection and displays a mismatch when the browser-advertised MIME type disagrees.

No surface has a fallback dataset or bundled success path. A failed import, parser error, CORS error, stopped server, or storage error remains visible where it occurred.

## Quality gates

```bash
npm ci --prefix docs
./sbt 'contentLab/test' 'pdfContentLab/test'
./sbt buildContentLab buildFrontend
./scripts/check-byte-streaming-hygiene.sh
npm run docs:build --prefix docs
```

Browser QA should include multiple similar binary files, a real PDF, a narrow viewport, a deliberate MIME mismatch, and a compatible or fail-closed font replacement attempt.

See the [CAS Playground](../cas-playground.md), [frontend module reference](../modules/frontend.md), and [HTTP API](../api/http.md).
