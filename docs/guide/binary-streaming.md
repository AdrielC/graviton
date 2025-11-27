# Binary Streaming Guide

Graviton treats every upload as a binary stream that becomes an ordered graph of blocks, manifests, and attributes. This guide collects the moving pieces so you can wire an ingest pipeline (CLI, gateway, or background job) without guessing how blocks are shaped or how metadata flows back to callers.

## Concept map

| Artifact | Description | Defined in |
| --- | --- | --- |
| **Block** | Canonical chunk of bytes with refined size bounds and a `BinaryKey.Block` derived from its content. Blocks are deduplicated globally. | `graviton.runtime.model.CanonicalBlock`, `BlockStore` |
| **Blob** | Logical object addressable via `BinaryKey`. Blobs reference manifests and carry attributes that survive deduplication. | `graviton.runtime.stores.BlobStore` |
| **Manifest** | Ordered list of block entries (`index`, `offset`, `key`, `size`) plus total length. Serialized as frames for durability and encryption. | `BlockManifest`, [`manifests-and-frames`](../manifests-and-frames.md) |
| **Attributes** | Tracked metadata split between advertised (client supplied) and confirmed (server verified) values such as size, MIME, and digests. | `graviton.core.attributes.BinaryAttributes` |
| **Chunker** | A `ZPipeline[Any, Throwable, Byte, Block]` that turns byte streams into canonical blocks. Chooses boundaries, normalization, and rechunking rules. | [`ingest/chunking`](../ingest/chunking.md) |

## End-to-end flow

```mermaid
sequenceDiagram
  autonumber
  actor Client
  participant Src as Byte Source
  participant Chunker as Chunker / ZPipeline
  participant Blocks as BlockStore
  participant Manifest as Manifest Builder
  participant Blob as BlobStore

  Client->>Src: Provide upload stream
  Src->>Chunker: Stream[Byte]
  Chunker->>Blocks: CanonicalBlock (dedupe check)
  Chunker-->>Manifest: Chunk stats
  Blocks-->>Manifest: StoredBlock + offsets
  Manifest->>Blob: BlockManifest + confirmed attrs
  Blob-->>Client: BlobWriteResult (key, locator)
  note over Client,Blob: Client can now read via BlobStore.get
```

1. A byte source (`ZStream` from files, HTTP bodies, etc.) feeds a chunker chosen for size vs deduplication trade-offs.
2. Each canonical block is hashed, typed, and streamed into the `BlockStore` sink returned by `putBlocks`.
3. The block batch result exposes stored vs duplicate blocks so manifests only list committed data.
4. Blob assembly wires the manifest together, enriches attributes (size, chunk count, MIME), and persists the manifest frame beside the blob pointer.
5. The caller receives a `BlobWriteResult` keyed by the logical blob hash and can immediately read the blob via `BlobStore.get`.

## Wiring chunkers, blocks, and manifests

<!-- snippet:binary-streaming-ingest:start -->
```scala
import graviton.core.attributes.{BinaryAttributes, Source, Tracked}
import graviton.core.bytes.{HashAlgo, Hasher}
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.model.ByteConstraints
import graviton.core.model.Block.*
import graviton.runtime.model.{BlockBatchResult, CanonicalBlock}
import graviton.runtime.stores.BlockStore
import graviton.streams.Chunker
import zio._
import zio.stream._

final case class Ingest(blockStore: BlockStore):
  private def wrapEither[A](either: Either[String, A]): Task[A] =
    ZIO.fromEither(either.left.map(msg => new IllegalArgumentException(msg)))

  private def canonicalBlock(block: Chunk[Byte], attrs: BinaryAttributes): Task[CanonicalBlock] =
    wrapEither {
      for
        digest     <- Hasher
                        .messageDigest(HashAlgo.Sha256)
                        .flatMap { hasher =>
                          val _ = hasher.update(block.toArray)
                          hasher.result
                        }
        bits       <- KeyBits.create(HashAlgo.Sha256, digest, block.length.toLong)
        key        <- BinaryKey.block(bits)
        chunkCount <- ByteConstraints.refineChunkCount(1L)
        confirmed   = attrs
                        .confirmSize(Tracked.now(ByteConstraints.unsafeFileSize(block.length.toLong), Source.Derived))
                        .confirmChunkCount(Tracked.now(chunkCount, Source.Derived))
        canonical  <- CanonicalBlock.make(key, block, confirmed)
      yield canonical
    }

  def run(bytes: ZStream[Any, Throwable, Byte]): Task[BlockBatchResult] =
    val attrs = BinaryAttributes.empty
    val sink  = blockStore.putBlocks()

    for
      chunkSize <- wrapEither(ByteConstraints.refineUploadChunkSize(1 * 1024 * 1024))
      result    <- bytes
                     .via(Chunker.fixed(chunkSize))
                     .mapZIO(block => canonicalBlock(block.bytes, attrs))
                     .run(sink)
    yield result
```
<!-- snippet:binary-streaming-ingest:end -->

_Snippet source: `docs/snippets/src/main/scala/graviton/docs/guide/BinaryStreamingIngest.scala` (managed via `sbt syncDocSnippets`)._

- **Backend-specific size caps**: use `ByteConstraints.enforceFileLimit(bytes, config.maxBlobBytes)` whenever you hydrate a backend config (filesystem quota, S3 object cap, etc.). The core `FileSize` refinement only ensures non-negative longs so each store can apply its own ceiling without fighting the type system.
- **Chunkers emit typed blocks**: Every chunker returns a `Block` that already satisfies `MaxBlockBytes` and related refined constraints.
- **Hashing before storage** keeps keys stable regardless of backend. The same block hash will deduplicate inside the filesystem, S3, or PostgreSQL stores.
- **`BlockWritePlan` controls framing**: choose compression, encryption, and whether duplicates should be forwarded downstream for multi-tenant replication.

## Attribute lifecycle

`BinaryAttributes` tracks provenance via `Tracked` values so the most trusted source wins. During ingest, write the best knowledge you have (advertised size, client MIME type). As the stream is chunked, confirm derived facts:

```scala
import graviton.core.attributes.{BinaryAttributes, Source, Tracked}
import graviton.core.bytes.HashAlgo

val initial = BinaryAttributes.empty
  .advertiseMime(Tracked.now("application/pdf", Source.ProvidedUser))

val confirmed = initial
  .confirmSize(Tracked.now(fileSize, Source.Derived))
  .confirmChunkCount(Tracked.now(blockCount, Source.Derived))
  .confirmDigest(HashAlgo.Sha256, Tracked.now(blobDigest, Source.Verified))
```

When the manifest is sealed, the confirmed attributes are persisted next to the blob key. Reads return the merged view so callers always see verified data when available.

Need structured change reports? The [`Schema-driven diffs`](../core/schema.md#schema-driven-diffs) section shows how to hang `zio.schema.Schema` instances off each `BinaryAttributeKey`, convert the advertised/confirmed maps into `DynamicValue.Record`s, and run `zio.schema.diff.Diff` (or even JSON diff tools) without giving up the `Tracked` provenance we rely on during ingest.

## Manifest composition and frames

Manifests enumerate blocks in order so retrieval is a pure streaming exercise:

1. `BlockManifestEntry` records the block index, byte offset, canonical block key, and uncompressed size.
2. `BlockManifest.build` validates that offsets never go backwards and that totals match the confirmed size.
3. `FrameSynthesis` chooses how the manifest and block frames are serialized (plain, compressed, encrypted) before shipping to a `BlobStore` implementation.

For an in-depth look at framing guarantees, encryption plans, and forward compatibility, see [`Manifests & Frames`](../manifests-and-frames.md).

## Frame codecs & streaming

- **Structured frame encoding**: `graviton.runtime.model.BlockFrameCodec.codec` is the canonical `scodec.Codec[BlockFrame]`. It keeps `FrameHeader` lengths honest (payload vs. AAD) and normalizes the authenticated data to a compact binary layout rather than ad-hoc JSON blobs.
- **Streaming transforms**: `BlockFrameStreams.encode`/`decode` expose `ZPipeline`s so you can push `ZStream[BlockFrame]` over the wire (gRPC, WebSocket, files) without buffering entire manifests. Compose them with compression/encryption pipelines to keep ingestion and replication purely streaming.
- **Aad helpers**: `BlockFrameCodec.renderAadBytes` mirrors the runtime encoder so external producers (Rust, Go, etc.) can stay byte-for-byte compatible by mimicking the emitted binary format.

## Chunking strategy quick reference

- **Fixed-size** chunking maximizes throughput and predictable offsets. Use for append-only logs or when deduplication is irrelevant.
- **FastCDC** balances speed and deduplication. Adjust normalization to bias toward smaller or larger blocks.
- **Anchored CDC** lets you split on format-aware anchors (PDF `endobj`, ZIP headers) for structured documents.
- **BuzHash / Rabin** provide classic rolling-hash behavior when cross-language parity matters.

The [Chunking Strategies guide](../ingest/chunking.md) provides detailed configuration snippets, decision trees, and performance notes.

## Retrieval & reassembly

Fetching a blob reverses the ingest pipeline:

1. `BlobStore.get` loads the manifest frame and attributes by blob key.
2. The runtime streams block keys through the `BlockStore`, optionally verifying digests on the fly.
3. Blocks are reassembled into a `ZStream[Byte]`. Partial reads use manifest offsets so large blobs can seek without decoding the entire payload.

Because manifest offsets and chunk counts are validated during ingest, retrieval never needs to buffer the whole object; the runtime can resume from any block boundary and still honor encryption or compression frames.

## Next steps

- Start from [`guide/getting-started`](./getting-started.md) to build and run the project locally.
- Dive into [`ingest/chunking`](../ingest/chunking.md) for algorithm-level tuning.
- Explore [`runtime/ports`](../runtime/ports.md) to see how stores, protocols, and schedulers compose inside the runtime.
