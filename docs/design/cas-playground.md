# CAS Playground design contract

## Thesis

The playground is a comparison workspace, not a metric-card dashboard or a hash worksheet. Real files become aligned range maps. The interface leads from file selection to exact shared blocks, then lets a PDF user make one safe edit and see what the edit actually changes.

The visual hierarchy follows the evidence:

1. Add files.
2. Read the exact reusable-byte result.
3. Compare aligned block tracks and select a content ID.
4. Inspect the matching byte ranges in every file.
5. Open secondary chunking controls only when the profile needs to change.
6. For a confirmed PDF, inspect embedded fonts and create a comparable font variant.
7. After a successful variant, show both exact CAS reuse and the bounded PDF structure delta.

No result is fabricated. Empty, loading, mismatch, incompatible-font, block-limit, and stream failures stay visible in the surface where the user can act on them.

## Operational workflow

- The picker accepts multiple PDFs, binaries, images, archives, and text files. Analysis begins immediately and runs for at most two files concurrently.
- Automatic mode reads the first five bytes. `%PDF-` selects ZIO PDF structural boundaries; other files use FastCDC. PDF structural, FastCDC, and fixed-range strategies remain explicit choices.
- The target can be 16 KiB, 64 KiB, 256 KiB, or 1 MiB. The interactive default is 64 KiB so ordinary documents produce a useful range map. Apply runs the current choice against every loaded file.
- Every block carries its byte range and SHA-256 content ID. Cyan means the exact block occurs in another loaded file. Violet means the currently selected content ID. Neutral ranges are unique within the comparison set.
- Selecting a range reveals the matching filename and byte range in each file. The detailed table provides the same operation for keyboard and screen-reader users.
- A byte-confirmed PDF loads the ZIO PDF module on demand, reports the document's actual font resources, and exposes only existing-resource remaps that can be checked safely.
- Creating a font variant fails closed when encoding, code widths, metrics, subtype, or ToUnicode data are incompatible. On success, it produces an unchanged canonical baseline and an edited variant through the same ZIO PDF encoder. This removes unrelated source-serialization differences from the CAS comparison.
- Before returning a generated variant, ZIO PDF validates the edited object graph. Its schema-backed `PdfDiff` then compares decoded components in 128-component LCS windows, verifies raw content-stream equality, accumulates totals without collecting the diff stream, and retains at most 12 change locations for the browser.
- CAS reuse and PDF structure remain orthogonal evidence. SHA-256 block identity answers which bytes are reusable; the PDF delta answers which objects, streams, or document metadata changed. The interface never substitutes one measure for the other or describes the windowed diff as globally minimal.
- After a successful font edit, the playground switches to automatic PDF chunking at 16 KiB and reanalyzes the original, canonical baseline, and edited variant. The smaller target makes a localized resource change legible without hardcoding that profile for ordinary comparisons.
- Generated PDFs are browser-local and downloadable from their file rows.

## Visual system

The component inherits the Graviton documentation tokens and supports both themes. Its hierarchy is comparison-first: upload owns the empty state, the measured reuse verdict leads the result state, byte tracks carry the evidence, and chunking configuration stays in a secondary disclosure.

- **Orbital green** (`--vp-c-brand-1`) marks primary actions, focus, progress, and the ready runtime. It is `#087f5b` in light mode and `#63e6be` in dark mode.
- **Reuse cyan** (`--graviton-cyan`) is reserved for byte ranges shared across files.
- **Exact-match violet** (`--graviton-violet`) identifies the selected content ID and its matches.
- **State colors** use `--graviton-success`, `--graviton-danger`, and `--graviton-gold` for success, failure, and loading or waiting states.
- **Surfaces** use `--graviton-panel`, `--graviton-panel-muted`, `--graviton-border`, `--graviton-ink`, and `--graviton-muted`. Borders and tonal layering carry most of the depth; the outer atlas receives one restrained ambient shadow.

Body copy uses the documentation sans stack, led by Avenir Next. Digests, ranges, byte totals, runtime state, and other machine evidence use the documentation mono stack, led by SFMono-Regular. Headlines are compact and slightly tightened. Labels are small and firm, while operational values use tabular numerals.

Corners are measured rather than bubbly: 16 px for the atlas, 12 px on the narrowest layout, and 7 to 8 px for controls and file markers. Rules separate stages without turning each result into a floating card.

## Responsive behavior

The atlas is an inline-size container, so it responds to its documentation column instead of the browser viewport alone.

- Above 760 px, profile controls open from the compact header disclosure, the reuse verdict and totals share one line, comparison evidence spans the available width, and font controls form one row.
- At 760 px and below, the verdict and totals stack, the selected-match panel becomes one column, and font controls stack.
- At 480 px and below, padding tightens, filenames truncate without hiding their file rows, selected matches stack, and section headers place controls below titles.
- The range table keeps its evidence columns and scrolls horizontally instead of collapsing labels into an ambiguous mobile card list.
- Compact homepage mode keeps upload, totals, and block maps, while omitting chunk-profile, font-editing, and range-table controls.

## Accessibility and motion

- The drop surface is a real file-input label with a visible `:focus-within` treatment. Native selects retain their platform behavior.
- Operational controls have a 44 px minimum target. Filter buttons expose `aria-pressed`; progress and runtime state use status semantics; failures use alert semantics.
- The decorative block track is pointer-selectable but hidden from assistive technology. Exact ranges remain operable through named buttons in the semantic table.
- Focus uses the documentation site's high-contrast three-pixel ring. Color is never the only source of selected-range detail because filenames, byte ranges, content IDs, and match counts are also present.
- Motion is limited to short state transitions on the drop target and progress transform. `prefers-reduced-motion` removes both transitions. There is no decorative entrance animation or continuous motion inside the tool.

## Runtime and data boundaries

The playground is local analysis, not a Graviton server client. It does not upload, persist, commit, or retrieve authoritative manifests. The browser `File` remains the source of truth, and generated PDFs exist only in memory until downloaded.

The analyzer requests explicit 64 KiB `Blob.slice()` reads, incrementally hashes the file, and materializes at most one refined block at a time. Blocks are capped at 4 MiB. The per-file metadata manifest is capped at 20,000 blocks, and the semantic range table paginates in groups of 240 without dropping keyboard access to later blocks. The UI reports an error instead of silently exceeding those limits.

PDF font inventory uses the incremental element stream. PDF rewriting is intentionally different: the current ZIO PDF transform materializes a decoded document graph. Both source input and each encoded output are capped at 32 MiB with named refined bounds. Files above that rewrite cap can still be chunked and inspected, but cannot create a browser font variant.

Post-transform structural validation shares that explicit 32 MiB browser bound. The subsequent semantic diff is streamed and uses bounded LCS tables. Raw payload retention is enabled for stream matches, but remains bounded by the same refined input size and the active 128-component window. Browser-facing change samples have an independent cap of 12.

The summary is a comparison estimate. Logical bytes are the sum of analyzed files; unique bytes count one copy of each exact block content ID; reusable bytes are their difference. It does not claim persisted storage savings, filesystem allocation, or compression. A running Graviton server's committed manifest remains authoritative.
