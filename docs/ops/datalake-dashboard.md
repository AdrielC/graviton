# Datalake Change Dashboard

<script setup>
import { onMounted } from 'vue'

const loadFrontendBundle = () => {
  if (typeof window === 'undefined') return
  const rawBase = import.meta.env?.BASE_URL ?? '/'
  const normalizedBase = rawBase === '/' ? '' : rawBase.replace(/\/$/, '')
  window.__GRAVITON_DOCS_BASE__ = normalizedBase
  if (!window.__GRAVITON_FRONTEND_LOADED__) {
    window.__GRAVITON_FRONTEND_LOADED__ = true
    import(`${normalizedBase}/js/main.js`).catch(err => {
      console.warn('Graviton frontend bundle not loaded:', err)
    })
  }
}

const attachSchemaSrc = () => {
  if (typeof document === 'undefined') return
  const element = document.getElementById('docs-schema-explorer')
  if (!element || element.dataset.initialized) return
  const apiMeta = document.querySelector('meta[name=graviton-api-url]')
  const apiBase = apiMeta?.getAttribute('content')?.trim() ?? ''
  const normalizedApi = apiBase.length > 0 ? apiBase.replace(/\/$/, '') : ''
  const schemaUrl = normalizedApi.length > 0 ? `${normalizedApi}/api/datalake/dashboard` : '/api/datalake/dashboard'
  element.setAttribute('data-src', schemaUrl)
  element.dataset.initialized = 'true'
}

onMounted(() => {
  loadFrontendBundle()
  attachSchemaSrc()
})
</script>

_Last updated: 2025-11-28 • Branch: `cursor/datalake-recent-changes-dashboard-gpt-5.1-codex-78c7`_

Keep a single page view of what changed across the ingest pipeline, runtime, and experience layers of the Graviton datalake.

## Executive Snapshot

| Pillar | Status | Evidence | Impact |
| --- | --- | --- | --- |
| Baseline health | ✅ Green | Phase 0 in `docs/logs/2025-11-06.md` captured clean `TESTCONTAINERS=0 ./sbt scalafmtAll test` and `npm run docs:build`. | CI inputs are reproducible; no hidden drift before new datalake work lands.
| Type & attribute foundations | 🚧 In progress | Phase 1 notes in `docs/logs/2025-11-06.md` detail new Iron-based `ByteConstraints`, opaque `Block` wrappers, and fresh specs. | Upload manifests now enforce sized blocks, with remaining interop helpers still tracked.
| Reliability & observability | ✅ Hardened | `CHANGES_SUMMARY.md` documents JVM memory caps, forked tests, digest fixes, and the resolution of 118/118 suites. | Long-running ingest jobs no longer OOM; test signal is trustworthy when evaluating pipeline regressions.
| Experience & surfacing | ✅ Live | `FINAL_STATUS.md` confirms the Laminar-powered dashboard (Stats Panel, Blob Explorer, Health Check) shipping with docs and CI wiring. | Product leaders can showcase datalake state directly in the published site while backend work evolves.

## Recent Highlights

### Ingest & Type Safety
- Restored Byte/Chunk refinements, `BlockBuilder`, and manifest-safe attributes per Phase 1 (`docs/logs/2025-11-06.md`).
- Added `ByteConstraintsSpec` and `BlockSpec`, giving regression coverage for size limits before they reach storage APIs.
- Extended `docs/ingest/chunking.md` to explain the new opaque wrappers and when to coerce legacy chunkers.

### Runtime Reliability & Tests
- Locked JVM memory budgets (2 GiB max / 512 MiB min), G1 GC, and per-suite forks, eliminating prior OOM + clock issues (see `CHANGES_SUMMARY.md`).
- Trimmed property-test sample sizes and data payloads, keeping ingest stress cases realistic while fitting within CI timeboxes.
- Digest handling bugs were patched, so multi-algorithm verification (SHA-256 + BLAKE3) in the datalake no longer leaks buffers.

### Experience & Insight Surfaces
- Scala.js frontend publishes a Stats Panel, Blob Explorer, and Health Check so stakeholders can inspect live datalake metrics (`FINAL_STATUS.md`).
- Docs theme overhaul (Matrix neon) keeps the dashboard discoverable: `docs/.vitepress/config.ts`, `theme/index.ts`, and `custom.css` now highlight Guide, Architecture, API, Scala.js, and Demo entry points (summarised in `CHANGES_SUMMARY.md`).
- Documentation build + deployment instructions (`DOCUMENTATION_STATUS.md`) ensure the dashboard can be previewed locally or via GitHub Pages.

## Change Stream (last 30 days)

| Date | Area | Update | Impact | Source |
| --- | --- | --- | --- | --- |
| 2025-11-06 | Tooling baseline | Reconfirmed clean repo state, ran `TESTCONTAINERS=0 ./sbt scalafmtAll test`, and rebuilt docs via npm as Phase 0 acceptance. | Guarantees future datalake patches start from a reproducible baseline. | `docs/logs/2025-11-06.md`
| 2025-11-06 | Ingest types | Added Iron `ByteConstraints`, opaque `Block`/`UploadChunk`, refreshed specs, and updated chunking docs (Phase 1 progress). | Enforces per-block safety before data touches BlockStore/BlobStore implementations. | `docs/logs/2025-11-06.md`
| 2025-10-30 | Docs infrastructure | Verified SBT + VitePress builds, outlined structure, and documented commands/endpoints. | Contributors can confidently regenerate the dashboard + docs site. | `DOCUMENTATION_STATUS.md`
| — | Reliability fixes | JVM tuning, digest leak fix, forked suites, and dead-link-free cyberpunk docs theme. | 118/118 tests pass; ingest simulations no longer hang or exhaust heap. | `CHANGES_SUMMARY.md`
| — | Experience layer | Delivered Laminar/Airstream dashboard modules, Scaladoc publishing, and CI wiring for GitHub Pages. | Provides a live datalake cockpit (Stats, Blob Explorer, Health) tied to every deploy. | `FINAL_STATUS.md`

## Health Indicators

### Build & Test

| Check | Command | Expected Result |
| --- | --- | --- |
| Format + unit/integration suites | `TESTCONTAINERS=0 ./sbt scalafmtAll test` | All suites green (~75s) with bounded heap (per `CHANGES_SUMMARY.md`).
| Docs site | `cd docs && npm install && npm run docs:build` | Neon VitePress build succeeds without dead-link warnings (per `docs/logs/2025-11-06.md`).
| Frontend artifacts | `./sbt buildFrontend && cd docs && npm run docs:dev` | Scala.js bundle compiles; demo route exposes dashboard (`FINAL_STATUS.md`).

### Operational Confidence
- **Metrics Surface**: Stats Panel highlights blob counts, storage usage, dedupe ratio, and health indicators (see `modules/frontend/README.md` via `FINAL_STATUS.md`).
- **Docs Accuracy**: Schema, chunking, ranges, and ingest guides tracked in `DOCUMENTATION_STATUS.md` remain in sync with current modules.
- **Navigation**: Top-level nav exposes Guide → Streaming → Architecture → API → Scala.js → Demo, keeping the dashboard just one click away (per `docs/.vitepress/config.ts`).

## Upcoming Focus
- Finish Phase 1 by documenting the new helpers and wiring additional BinaryAttribute interop (from `docs/logs/2025-11-06.md`).
- Kick off Phase 2 extraction of FastCDC + rolling hash chunkers and the selection heuristics referenced in the log’s workstreams.
- Prepare the mime sniffer design doc + module skeleton so Phase 3 can attach hints to the ingest dashboard metrics.
- Sketch mdoc integration (Phase 7) so dashboard snippets stay executable before larger doc merges land.

## Source Index
- `docs/logs/2025-11-06.md` — Phase-by-phase migration log and acceptance checks.
- `CHANGES_SUMMARY.md` — Reliability hardening + Matrix-themed docs upgrades.
- `DOCUMENTATION_STATUS.md` — Build verification and site structure snapshot.
- `FINAL_STATUS.md` — Scala.js dashboard / frontend deliverables and CI wiring details.

## API Integration
- `GET /api/datalake/dashboard` now returns a `DatalakeDashboardEnvelope` that includes the live snapshot plus a metaschema generated via `Schema[DatalakeDashboard].ast`. Clients can diff the AST to detect contract changes.
- `GET /api/datalake/dashboard/stream` exposes a server-sent-event feed powered by a ZIO `Hub` + `ZStream`, allowing the Scala.js demo (and any other consumer) to stay in sync without polling.
- The metaschema uses the `zio-schema-json` codec so downstream tooling can hydrate the AST into whatever representation they need.
- The response also ships a `schemaExplorer` graph derived from `Schema.makeAccessors`, giving UIs enough structure to build interactive editors or schema visualizations without bespoke wiring.

## Interactive Editing & Accessors
- The `/demo#/updates` route now renders editable string fields straight from the schema-derived accessors. Every input is produced by traversing the `Schema[DatalakeDashboard]` structure—no hand-maintained forms.
- When the dashboard loads data (or receives SSE updates) the editor state synchronizes automatically; you can tweak values locally and click **Apply edits** to mutate the in-browser snapshot. It’s a proof-of-concept for moving “functions to the data” where the schema itself drives the editing experience.
- For richer tooling, the frontend also exposes a `graviton-schema` custom element (and an inline Laminar `SchemaExplorerView`) so other docs/pages can embed live schema explorers backed by the same accessor metadata. A live instance is embedded below.

<meta name="graviton-api-url" content="http://localhost:8081" />

:::tip Configure API for docs
Update the `<meta name="graviton-api-url" />` tag above so the embedded explorer knows where to fetch `/api/datalake/dashboard`.
:::

<ClientOnly>
<div class="docs-schema-panel">
  <graviton-schema id="docs-schema-explorer"></graviton-schema>
</div>
</ClientOnly>
