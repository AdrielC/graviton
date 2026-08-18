# Apache Tika Integration (Planned)

Graviton does not currently ship a Tika module in this repository. This page is a **design note** for a future integration that enriches ingest with MIME detection, structured metadata, and (optionally) extracted text for indexing.

## What it provides

- **Detector + parser orchestration:** Wraps Tika's detector and parser registries so uploads can be typed before they hit a backend.
- **Metadata → `BinaryAttributes` bridge:** Maps Tika's `Metadata` bag into strongly typed `BinaryAttributes`, separating advertised vs. confirmed keys.
- **Text extraction stream:** Emits UTF-8 text content into a side channel so search indexes can trail ingestion.
- **Policy-aware fallback:** Short-circuits to "binary only" mode whenever parsing exceeds configured size or time limits; the ingest flow keeps moving.

| Component | Description |
| --- | --- |
| `TikaConfig` | Pool sizing, parser timeout, excluded media types, and max extracted bytes. |
| `TikaLayers.live` | Bundles detector, parser pool, metrics, and logging wiring into a single `ZLayer`. |
| `TikaAttributeMapper` | Normalizes metadata keys (content language, author, producer, etc.) into `BinaryAttributeKey`s. |
| `TikaTextChannel` | Optional sink that receives extracted text chunks for indexing or auditing. |

## Supported formats (out of the box)

| Category | Examples |
| --- | --- |
| Office | DOC/DOCX, PPT/PPTX, XLS/XLSX, ODF |
| PDF & PS | PDF 1.x/2.0, PostScript, XPS |
| Media | JPEG/PNG/GIF, TIFF with embedded metadata, MP3/MP4 container tags |
| Archives | ZIP/JAR, 7z, TAR.GZ (metadata only) |
| Text & markup | HTML/XHTML, Markdown, JSON, XML |

> Tika automatically negotiates the correct parser when it can read the full media type. Use the configuration knobs below to restrict or extend this list.

## Configuration

Declare the module-specific settings in your application config (HOCON shown):

```
graviton.tika {
  parser-pool-size   = 4
  max-bytes-per-doc  = 8MiB
  max-bytes-per-field = 512KiB
  timeout            = 15s
  allow-media-types  = ["application/pdf", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"]
  deny-media-types   = ["application/octet-stream"]
  emit-text-channel  = true
}
```

Key considerations:

- **Pool sizing:** Keep the parser pool small but non-zero; every worker holds native resources.
- **Safety rails:** `max-bytes-per-doc` prevents pathological files from exhausting heap space. Timeouts protect the upload path from deep recursion bugs in third-party parsers.
- **Allow/deny filters:** Reduce CPU burn by short-circuiting file types that already carry structured metadata (e.g., `.parquet`) or that do not benefit from text extraction.

## Wiring example

Until the module exists, think of it as an optional “enricher” that runs during ingest:

```scala
// pseudo-code (API will change once the module lands)
trait BinaryAttributeEnricher {
  def enrich(input: zio.stream.ZStream[Any, Throwable, Byte]): zio.IO[Throwable, graviton.core.attributes.BinaryAttributes]
}

// runtime ingest would:
// 1) sniff/parse bytes (bounded)
// 2) convert metadata -> BinaryAttributes (advertised vs confirmed)
// 3) write attributes alongside the manifest
```

This example assumes the ingest service consumes a `BinaryAttributeEnricher` to append metadata before persisting manifests. The Tika layer can either become that enricher or feed a `FiberRef` that the ingest fiber reads while building the manifest.

## Metrics and logging

- `MetricKeys.TikaParsed` increments for every successfully parsed object.
- `MetricKeys.TikaRejected` tracks files that exceeded limits or matched the deny list.
- `MetricKeys.TikaLatency` (histogram) captures parser time so you can tune pool sizes.
- Each parse includes structured logs with media type, detector confidence, and byte counts. You can correlate them with `correlationId` propagated through the ingest fiber.

## Operational checklist

1. Keep parsers on the classpath but trim unused ones to reduce CVE surface area.
2. Bound parsing (bytes + time) so ingest remains streaming and resilient.
3. Add integration tests that feed representative files through ingest with the Tika layer enabled; assert metadata was persisted inside `BinaryAttributes`.

## Roadmap

- Binary diffing for extracted text to avoid re-indexing unchanged documents.
- Configurable extraction profiles (metadata-only vs. metadata + text).
- Structured error taxonomy surfaced via `BinaryAttributeError` so clients know why enrichment failed.
- Optional streaming hand-off to search systems (OpenSearch, Meilisearch) via `TikaTextChannel`.
