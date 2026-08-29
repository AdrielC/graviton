# Chunking Strategies

Graviton turns an input byte stream into bounded `Block` values before it hashes and stores them. The selected chunker changes block boundaries and deduplication behavior. It does not change the logical bytes or the blob key.

## Implemented chunkers

| Strategy | Artifact | Boundary | Upper bound |
| --- | --- | --- | --- |
| Fixed | `graviton-streams` | Every configured number of bytes | Configured size |
| FastCDC | `graviton-streams` | Rolling content fingerprint | Configured maximum |
| Delimiter | `graviton-streams` | Streaming KMP match | Configured maximum |
| PDF-aware | `graviton-pdf` | Complete PDF indirect object near the target | Configured maximum |

Anchored CDC, BuzHash, Rabin, and ZIP-aware cuts are not public Graviton APIs today.

## Streaming contract

Every strategy implements the same interface:

```scala
trait Chunker:
  def name: String
  def pipeline: ZPipeline[Any, Chunker.Err, Byte, Block]
```

The pipeline is incremental. It may receive transport chunks of any size, preserves byte order, emits blocks before the upload completes, and retains no more than its configured maximum in-flight block. The final partial block is emitted at end of input.

Use Iron-refined sizes where the API accepts them:

```scala
import graviton.core.types.UploadChunkSize
import graviton.streams.Chunker
import zio.stream.ZStream

val blockSize = UploadChunkSize(1024 * 1024)

val blocks =
  ZStream
    .fromFileName("archive.bin", chunkSize = 64 * 1024)
    .via(Chunker.fixed(blockSize).pipeline)
```

`Chunker.Err` reports configuration, block, delimiter, and format violations. `Chunker.toThrowable` bridges this typed channel at an API boundary such as `BlobStore`.

## Fixed size

```scala
val chunker = Chunker.fixed(UploadChunkSize(1024 * 1024))
```

Fixed boundaries are predictable and cheap. An insertion near the beginning of a document shifts every later boundary, so fixed chunking is usually weaker for deduplicating edited files.

Use it for append-only data, fixed records, already-compressed content, or workloads where predictable storage requests matter more than shift resistance.

## FastCDC

```scala
val chunker = Chunker.fastCdc(
  min = 256 * 1024,
  avg = 1024 * 1024,
  max = 4 * 1024 * 1024,
)
```

FastCDC advances a rolling fingerprint byte by byte and cuts when the fingerprint matches a mask. It never emits a block larger than `max`. The constructor normalizes invalid runtime values into a safe range, but applications should still validate configuration before startup rather than rely on normalization.

Use FastCDC when insertions or deletions should preserve many later block identities. Measure the result on representative data because smaller blocks improve boundary granularity while increasing manifest entries and backend requests.

## Delimiter

```scala
import zio.Chunk

val newline = Chunk('\n'.toByte)
val chunker = Chunker.delimiter(
  delim = newline,
  includeDelimiter = true,
  minBytes = 1,
  maxBytes = 1024 * 1024,
)
```

Delimiter matching uses an incremental KMP state machine, so a delimiter split across upstream transport chunks is still found. `maxBytes` forces a cut when no delimiter arrives. Empty delimiters are rejected.

Use it for line-oriented or framed formats whose delimiters are stable and unambiguous.

## PDF-aware

Add the module to an embedded application:

```scala
libraryDependencies += "io.github.adrielc" %% "graviton-pdf" % gravitonVersion
```

Then stream an advertised PDF into the CAS:

```scala
import graviton.pdf.PdfIngest
import zio.pdf.PdfMime
import zio.stream.ZStream

val result = PdfIngest.put(
  store = graviton.blobStore,
  advertised = PdfMime.mimeType,
  bytes = ZStream.fromFileName("report.pdf", chunkSize = 64 * 1024),
)
```

`PdfAwareChunker` feeds each transport chunk into zio-pdf's incremental object scanner. It prefers the first complete indirect-object boundary at or after the target size. It forces a cut at the maximum size even when one PDF object is larger.

The default configuration is:

| Setting | Default |
| --- | ---: |
| Target block | 1 MiB |
| Maximum block | 4 MiB |
| zio-pdf structural carry | 1 MiB |
| Unsupported structure | Bounded fixed-size fallback |

The chunker validates the `%PDF-` signature. At an emission boundary it owns at most 9 MiB plus 5 bytes per active upload: one mutable 4 MiB block, one immutable emitted 4 MiB block, 1 MiB of parser carry, and the signature. Upstream chunks and application queues are additional. Strict callers can select `UnsupportedStructurePolicy.Reject` instead of the fallback.

HTTP and gRPC clients do not need to select the chunker directly. The packaged server inspects a bounded prefix, resolves the effective media type, and acquires a fresh provider for that upload. The current detector recognizes the five-byte `%PDF-` signature. Unknown formats use the configured general-purpose chunker.

If `Content-Type` is omitted or is `application/octet-stream`, a detected PDF uses PDF-aware chunking. A concrete claim such as `text/plain` that disagrees with detected PDF bytes fails before a provider or manifest is created. A claimed `application/pdf` whose bytes do not start with `%PDF-` fails after the five-byte probe. Graviton does not pretend to have detected formats for which no detector is registered.

See [PDF-aware ingest](../modules/pdf.md) for configuration, failure behavior, and executable proof.

## Automatic provider selection

`UploadIngestor` owns the one-pass upload decision. Its order is fixed:

1. Validate and normalize the advertised ZIO Blocks `MediaType`.
2. Enforce an optional declared byte length on the live stream.
3. Retain and replay one Iron-bounded prefix of at most 4 KiB.
4. Run registered detectors over that bounded value.
5. Select an exact media-type provider, falling back only to the default provider.
6. Acquire one chunker in the upload `Scope`, stream the complete body through it, and release it on success, failure, or interruption.

Detectors may run concurrently because they receive the same bounded immutable prefix. Length validation, storage, and hashing remain one stream. Graviton does not fork the request body into competing consumers.

Providers are keyed services. The registry uses ZIO 2.1 `serviceAt` lookup over `Map[ChunkerProvider.Key, ChunkerProvider]`:

```scala
import graviton.runtime.upload.*
import zio.*

val provider = ChunkerProvider.make(
  ChunkerProviderId("archive-aware")
) { context =>
  ZIO.acquireRelease(
    prepareChunker(context.probe)
  )(releaseChunker)
}

val providers = Map(
  ChunkerProvider.Key.Default -> ChunkerProvider.current,
  ChunkerProvider.Key.MediaType(archiveMediaTypeKey) -> provider,
)
```

The acquisition effect may initialize parser state, temporary workspace, native handles, or other request-specific resources. Its finalizer stays installed until the CAS write ends. Provider IDs and media-type keys are refined types rather than free-form routing strings.

## Fiber-local installation

`Chunker.current` is a `FiberRef`. Runtime writes read the current value, and `Chunker.locally` changes it only for a region:

```scala
Chunker.locally(chunker) {
  byteStream.run(blobStore.put())
}
```

The provider registry chooses and acquires the chunker. `Chunker.locally` is the transparent handoff into the existing storage sink. The previous value is restored on success, failure, or interruption, so concurrent uploads can select different policies without threading a chunker argument through every internal method.

## Pure state machine

`ChunkerCore` exposes fixed, FastCDC, and delimiter logic without ZIO effects:

```scala
import graviton.streams.ChunkerCore
import zio.Chunk

val initial = ChunkerCore
  .init(ChunkerCore.Mode.FastCdc(256, 1024, 4096))
  .toOption
  .get

val (next, emitted) = initial.step(Chunk(1, 2, 3, 4)).toOption.get
val (_, finalBlocks) = next.finish.toOption.get
```

This is useful for tests, benchmarks, or adapters to another streaming runtime. It has the same bounded buffer and block invariants as the ZIO pipeline.

## Choosing a strategy

| Data shape | Start with | Reason |
| --- | --- | --- |
| PDF | PDF-aware | Stable structural boundaries with bounded fallback |
| Edited documents or source archives | FastCDC | Better shift resistance |
| Logs or newline records | Delimiter | Natural record boundaries |
| Already-compressed or fixed records | Fixed | Low overhead and predictable I/O |
| Unknown binary data | FastCDC or fixed | No format-specific assumptions |

Changing a chunker changes manifests and deduplication behavior for newly written blobs. Content keys remain based on the full logical bytes, so retrieval identity does not change.

## What to measure

Before changing production policy, record:

- total input bytes and input count
- block count and block-size distribution
- fresh and duplicate block counts
- backend request count and latency
- upload and retrieval throughput
- peak heap under the intended concurrency
- behavior after small insertions, deletions, and reordered sections

Do not infer deduplication savings from average block size alone.

## Verification

```bash
./sbt streams/test pdf/test
./scripts/probe-pdf-ingest.sh path/to/document.pdf
```

The PDF probe uses a temporary filesystem CAS and compares the streamed retrieval's byte count and SHA-256 with the source. It never collects the document.

## See also

- [Binary Streaming](../guide/binary-streaming.md)
- [End-to-end Upload](../end-to-end-upload.md)
- [Manifests and Frames](../manifests-and-frames.md)
- [Performance Measurement](../ops/performance.md)
