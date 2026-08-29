# PDF-aware Ingest

`graviton-pdf` connects [zio-pdf](https://github.com/AdrielC/zio-pdf) to Graviton's real CAS write path. It is a JVM module for applications that need PDF-specific block boundaries without loading an entire document into memory.

## What is operational

- `PdfIngest.put` accepts a ZIO Blocks `MediaType` and a `ZStream[..., Byte]`.
- The first five bytes must match `%PDF-` when the upload is advertised as `application/pdf`.
- zio-pdf's incremental `PdfObjectScanner` finds completed indirect-object boundaries across arbitrary transport chunks.
- The chunker prefers the first object boundary at or after the target size.
- An oversized object is cut at the configured maximum block size.
- Unsupported structural forms use bounded fixed-size cuts by default. Strict callers can reject them instead.
- HTTP, the packaged gRPC server, and Shardcake upload owners share the same automatic selection path.
- An omitted or generic `application/octet-stream` content type is upgraded to `application/pdf` only when the bounded signature detector confirms `%PDF-`.
- A concrete media-type mismatch fails before the PDF provider or manifest is created.

The parser and CAS writer receive the upload as a stream. Neither API collects the full document.

## Dependency

Use the same Graviton version as the rest of your application:

```scala
libraryDependencies += "io.github.adrielc" %% "graviton-pdf" % gravitonVersion
```

## Embedded use

```scala
import graviton.pdf.PdfIngest
import graviton.runtime.Graviton
import zio.*
import zio.pdf.PdfMime
import zio.stream.ZStream

import java.nio.file.Paths

val program =
  for
    graviton <- Graviton.fs(Paths.get(".graviton"))
    result   <- PdfIngest.put(
                  store = graviton.blobStore,
                  advertised = PdfMime.mimeType,
                  bytes = ZStream.fromFileName("report.pdf", chunkSize = 64 * 1024),
                )
    valid    <- graviton.verify(result.key)
  yield result.key -> valid
```

`PdfIngest.put` delegates to the same `UploadIngestor` used by protocol servers. The PDF provider creates a fresh `PdfAwareChunker` for each upload and installs it with `FiberRef` regional scoping. A caller's existing `Chunker.current` value is restored when the effect succeeds, fails, or is interrupted.

## Bounds and failure policy

The default configuration owns at most:

```text
4 MiB in-flight block + 4 MiB immutable emitted block + 1 MiB parser carry + 5 signature bytes
= 9 MiB + 5 bytes per active PDF ingest
```

This figure covers memory owned by `PdfAwareChunker`. An application's upstream stream chunks and any explicitly configured queues are additional and must also be bounded.

```scala
import graviton.core.types.UploadChunkSize
import graviton.pdf.PdfAwareChunker

val strict = PdfAwareChunker.Config.make(
  targetBytes = UploadChunkSize(1024 * 1024),
  maxBytes = UploadChunkSize(4 * 1024 * 1024),
  maxCarryBytes = UploadChunkSize(1024 * 1024),
  unsupportedStructure = PdfAwareChunker.UnsupportedStructurePolicy.Reject,
)
```

`Config.make` rejects a target larger than the maximum. Size fields are Iron-refined `UploadChunkSize` values rather than unvalidated integers.

## Proof commands

The module and runtime suites cover fragmented transport chunks, signature mismatch, automatic detection, concrete media-type mismatch, object-boundary cuts, bounded fallback, strict rejection, CAS round trips, provider release, interruption, early termination, and the declared memory ceiling.

```bash
./sbt pdf/test
./scripts/probe-pdf-ingest.sh path/to/large-document.pdf
./scripts/verify-external-consumer.sh
```

The probe streams a real file into a temporary filesystem CAS, streams it back, compares byte count and SHA-256, and runs Graviton's independent verification path. It does not retain the temporary store.

## Deliberate limits

This module validates a PDF signature and derives storage boundaries. It does not yet perform page extraction, font analysis, semantic chunking, repair, or malware screening. Those are separate parsing and policy concerns and should not be inferred from successful CAS ingest.
