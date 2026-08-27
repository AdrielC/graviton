# Migrating from 0.3 to 0.4

Graviton 0.4 added an operational PDF-aware ingest module. Its `0.4.0` POM incorrectly selected ZIO Blocks schema and chunk `0.017` after trusting Maven's `<latest>` metadata. That artifact is an older, malformed publication of the `0.0.17` code line, not a successor to `0.0.51`.

> The dependency repair did not ship as 0.4.1. Upgrade directly to [Graviton 0.5](./migration-0.5.md), which pins every ZIO Blocks module to `0.0.51` and includes the required clean-compile repair for the public Scala 3 refined-type hierarchy.

Stored content keys, blocks, manifests, and HTTP blob identifiers remain compatible. Applications on `0.4.0` should upgrade all Graviton modules together.

## What changed

| 0.3 behavior | Current 0.4 behavior | Migration |
| --- | --- | --- |
| Every HTTP upload used the server's general-purpose chunker | `Content-Type: application/pdf` uses bounded PDF-aware chunking | No client change for valid PDFs. Bytes falsely advertised as PDF now receive `400 Bad Request`. |
| No PDF-specific artifact | `graviton-pdf` exposes `PdfIngest` and `PdfAwareChunker` | Add the module only to embedded applications that call the PDF API directly. `graviton-http` already depends on it. |
| ZIO Blocks schema/chunk `0.0.51` | ZIO Blocks schema/chunk `0.0.51` | If upgrading from `0.4.0`, evict the malformed `0.017` artifact and verify `0.0.51` is selected. |
| ZIO Blocks media type `0.0.51` | ZIO Blocks media type `0.0.51` | No media-type version change is required. |
| scodec streams could complete silently at truncated EOF and retained unlimited undecoded carry | `ZStreamDecoder.once` and `many` reject truncated EOF and cap undecoded carry at 32 MiB by default | Use the explicit bounded overload for a larger known value. Use `tryOnce` or `tryMany` only when permissive termination is intentional. |
| Legacy MIME strings received minimal length validation | Transport and PDF boundaries validate and canonically render ZIO Blocks `MediaType` values | Fix malformed parameters and control characters. PDF ingest accepts exactly `application/pdf`, not wildcard media ranges. |

## Dependency alignment

Do not mix Graviton 0.4 and 0.5 modules in one application. Use one `gravitonVersion` for every artifact and run the build's eviction check after upgrading.

```scala
val gravitonVersion = "0.5.0"

libraryDependencies ++= Seq(
  "io.github.adrielc" %% "graviton-runtime" % gravitonVersion,
  "io.github.adrielc" %% "graviton-http" % gravitonVersion,
  "io.github.adrielc" %% "graviton-pdf" % gravitonVersion,
)
```

The `graviton-pdf` artifact uses zio-pdf `0.2.0-RC7`, which is published against ZIO Blocks `0.0.51`. Graviton also pins schema, chunk, and media type `0.0.51` directly so the selected graph is explicit.

The build's temporary dependency-policy exception covers only the malformed `0.017` to `0.0.51` repair. Graviton's exposed ZIO Blocks register descriptors and public APIs are compatibility-tested, but ZIO Blocks itself is not globally binary-compatible across those coordinates. Applications that directly used `0.017` APIs should clean-recompile and run their own integration tests.

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

The `v0.4.0` release documented the original dependency boundary. Graviton 0.5 intentionally changes three implementation-trait hierarchies to repair unusable downstream TASTy, while stored formats remain unchanged. See [Migrating from 0.4 to 0.5](./migration-0.5.md) for the clean-recompile requirement. Decoder EOF, carry limits, content-key parsing, and MIME validation are intentionally stricter as described above.
