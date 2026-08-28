---
layout: doc
title: CAS Playground
---

# CAS Playground

Choose real files. Graviton streams, chunks, and hashes them locally, then shows the exact ranges they share.

<PipelinePlayground />

## How comparison works

This is the real Scala.js analysis path, not a simulated result:

- **Automatic** uses ZIO PDF structural boundaries when the first five bytes are `%PDF-`, and FastCDC for every other file.
- **PDF structures** cuts at complete PDF object boundaries when possible and falls back to bounded ranges if structural scanning cannot continue.
- **FastCDC** resists boundary shifts after small insertions or deletions.
- **Fixed ranges** makes exact offset-aligned comparison explicit.
- SHA-256 is incremental. The browser module requests 64 KiB `Blob.slice()` reads and passes 64 KiB digest windows to `@noble/hashes`.
- Cross-file reuse counts only exact block content ID matches.

The interactive default is 64 KiB. Choose 16 KiB, 64 KiB, 256 KiB, or 1 MiB and apply the profile to every loaded file. A block is never larger than 4 MiB, the result manifest is capped at 20,000 blocks per file, and the UI analyzes at most two files concurrently.

::: tip Local by design
The static documentation site does not upload or persist these files. The browser-owned `Blob` stays the source of truth while Scala.js requests explicit 64 KiB slices. To write an authoritative manifest, use a running Graviton server through the [operations console](./demo.md), CLI, HTTP API, or Scala SDK.
:::

## Compare a PDF edit

When the byte signature confirms a PDF, the page loads a second Scala.js module built from `zio-pdf` 0.2.0-RC7. Font inventory is read from the incremental PDF element stream. The inventory count includes every detected font resource; the source and replacement controls show the subset that ZIO PDF can safely evaluate for an existing-resource remap. Nothing comes from a hardcoded font catalog.

**Build variant** produces two local outputs through the same ZIO PDF encoder: an unchanged canonical source and a font-remapped variant. It switches to the 16 KiB automatic profile and analyzes both outputs, so reuse measures the font edit instead of unrelated differences in serialization. Both outputs can be downloaded.

The font transformation fails closed unless subtype, encoding, widths, metrics, and `ToUnicode` data prove the existing glyph codes retain their meaning. A successful rewrite means the resource binding passed those checks; it does not claim that every PDF or every pair of fonts is interchangeable.

PDF inspection remains streaming. Rewriting intentionally has a separate 32 MiB input and output limit because the current transform builds a decoded document graph. Both sides of that materialization are enforced with named Iron refinements; files above the limit can still be chunked and inspected.

## Interpreting the result

The top summary treats the loaded files as one candidate content-addressed collection:

| Value | Meaning |
| --- | --- |
| Logical bytes | Sum of analyzed file lengths |
| Unique bytes | One copy of each distinct block content ID |
| Reusable bytes | Logical bytes minus unique bytes |
| Shared range | A block whose exact content ID occurs in another loaded file |

Changing a PDF font resource can leave most encoded objects unchanged. Comparing canonical and edited outputs at the 16 KiB PDF-aware profile keeps those unchanged objects independent from the rewritten resources and cross-reference tail. Results remain document-dependent and are reported only from exact SHA-256 block matches.

## Browser and server boundaries

The playground profile is chosen by the visitor and exists only for comparison. A Graviton server may use a different target, maximum, or chunking strategy. Its committed manifest is authoritative for durable storage and retrieval.

Start a local filesystem-backed server and console with:

```bash
GRAVITON_CONSOLE_ENABLED=true \
  ./sbt "server/run"

open http://127.0.0.1:8081/console
```

## Continue

- [Run Graviton locally](./guide/run-locally.md)
- [Chunking Strategies](./ingest/chunking.md)
- [Binary Streaming](./guide/binary-streaming.md)
- [HTTP API](./api/http.md)
