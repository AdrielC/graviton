# Migrating from 0.3 to 0.4

Graviton 0.4 adds an operational PDF-aware ingest module and moves the ZIO Blocks schema and chunk dependencies from `0.0.51` to `0.017`. The dependency transition is an intentional pre-1.0 compatibility boundary. Graviton's Scala APIs remain MiMa-compatible with 0.3, and stored content keys, blocks, manifests, and HTTP blob identifiers remain compatible.

## What changed

| 0.3 behavior | 0.4 behavior | Migration |
| --- | --- | --- |
| Every HTTP upload used the server's general-purpose chunker | `Content-Type: application/pdf` uses bounded PDF-aware chunking | No client change for valid PDFs. Bytes falsely advertised as PDF now receive `400 Bad Request`. |
| No PDF-specific artifact | `graviton-pdf` exposes `PdfIngest` and `PdfAwareChunker` | Add the module only to embedded applications that call the PDF API directly. `graviton-http` already depends on it. |
| ZIO Blocks schema/chunk `0.0.51` | ZIO Blocks schema/chunk `0.017` | Upgrade all Graviton artifacts together and align any direct ZIO Blocks schema/chunk dependencies in the application. |
| ZIO Blocks media type `0.0.51` | ZIO Blocks media type `0.0.51` | No media-type version change is required. |

## Dependency alignment

Do not mix Graviton 0.3 and 0.4 modules in one application. Use one `gravitonVersion` for every artifact and run the build's eviction check after upgrading.

```scala
val gravitonVersion = "0.4.0"

libraryDependencies ++= Seq(
  "io.github.adrielc" %% "graviton-runtime" % gravitonVersion,
  "io.github.adrielc" %% "graviton-http" % gravitonVersion,
  "io.github.adrielc" %% "graviton-pdf" % gravitonVersion,
)
```

The `graviton-pdf` artifact uses zio-pdf `0.2.0-RC6`, ZIO Blocks schema/chunk `0.017`, and ZIO Blocks media type `0.0.51`.

## PDF upload behavior

The HTTP API parses `Content-Type` with ZIO Blocks. For `application/pdf`, it checks `%PDF-` and passes the request body through zio-pdf's incremental object scanner. A complete PDF is never collected. At an emission boundary, the default chunker owns at most 9 MiB plus 5 bytes per active PDF upload, including its mutable block, one immutable emitted block, and parser carry. Upstream transport chunks and explicitly configured queues are additional.

If an embedded application must reject a structural form that zio-pdf cannot safely scan, select `UnsupportedStructurePolicy.Reject`. The default server uses bounded fixed-size fallback so valid but structurally unusual PDFs remain storable.

See [PDF-aware ingest](../modules/pdf.md) for the API and proof commands.

## Deployment check

Before moving production traffic:

1. Upgrade every Graviton module in the application together.
2. Run dependency eviction checks.
3. Upload one representative PDF and one non-PDF blob through the real HTTP server.
4. Confirm a falsely advertised PDF is rejected with `400`.
5. Stream both stored objects back and compare their digests.

No data migration is required for existing filesystem, S3, or PostgreSQL-backed CAS content.

## Compatibility policy

The build records the 0.4 development line with `Compatibility.None` because ZIO Blocks uses early semantic versioning and the schema/chunk dependency moves from `0.0.51` to `0.017`. This is a dependency-level boundary, not a silent claim of patch compatibility. After `v0.4.0`, development returns to binary-compatible intent until another documented 0.x minor boundary.
