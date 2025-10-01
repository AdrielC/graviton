package graviton.ingest

import graviton.*
import graviton.BlockKey
import graviton.chunking.Chunker
import graviton.core.{BinaryAttributeKey, BinaryAttributes}
import graviton.core.model.Block
import graviton.Manifest
import graviton.ManifestEntry
import zio.*
import zio.stream.*

final case class IngestResult(
  blobKey: BlobKey,
  manifest: Manifest,
  attributes: BinaryAttributes,
  totalBlocks: Int,
)

object FileIngestor {

  private val IngestSource = "file-ingestor"

  def ingest(
    bytes: Bytes,
    blockStore: BlockStore,
    chunker: Chunker,
    hashAlgorithm: HashAlgorithm = HashAlgorithm.SHA256,
    advertisedAttributes: BinaryAttributes = BinaryAttributes.empty,
  ): IO[GravitonError, IngestResult] =
    for {
      _          <- BinaryAttributes.validate(advertisedAttributes)
      hasher     <- Hashing.hasher(hashAlgorithm)
      sizeRef    <- Ref.make(0L)
      offsetRef  <- Ref.make(0L)
      entriesRef <- Ref.make(Vector.empty[ManifestEntry])
      stream      = bytes
                      .mapError(e => GravitonError.BackendUnavailable(Option(e.getMessage).getOrElse(e.toString)))
                      .tapChunks { chunk =>
                        hasher.update(chunk) *> sizeRef.update(_ + chunk.length.toLong)
                      }
                      .via(chunker.pipeline)
                      .mapError(e => GravitonError.BackendUnavailable(Option(e.getMessage).getOrElse(e.toString)))
                      .mapZIO(storeBlock(blockStore, offsetRef, entriesRef))
      blockCount <- stream
                      .runFold(0)((acc, _) => acc + 1)
                      .mapError(e => GravitonError.BackendUnavailable(Option(e.getMessage).getOrElse(e.toString)))
      digest     <- hasher.digest
      totalSize  <- sizeRef.get
      manifest   <- entriesRef.get.map(entries => Manifest(entries))
      blobKey     = BlobKey(
                      hash = Hash(digest, hashAlgorithm),
                      algo = hashAlgorithm,
                      size = totalSize,
                      mediaTypeHint = advertisedAttributes
                        .getConfirmed(BinaryAttributeKey.contentType)
                        .map(_.value)
                        .orElse(
                          advertisedAttributes
                            .getAdvertised(BinaryAttributeKey.contentType)
                            .map(_.value)
                        ),
                    )
      attributes  = advertisedAttributes ++ BinaryAttributes.confirmed(
                      BinaryAttributeKey.size,
                      totalSize,
                      IngestSource,
                    )
    } yield IngestResult(blobKey, manifest, attributes, blockCount)

  private def storeBlock(
    blockStore: BlockStore,
    offsetRef: Ref[Long],
    entriesRef: Ref[Vector[ManifestEntry]],
  )(
    block: Block
  ): IO[GravitonError, BlockKey] =
    val chunk = block.toChunk
    for {
      key    <- ZStream
                  .fromChunk(chunk)
                  .run(blockStore.put)
                  .mapError(e => GravitonError.BackendUnavailable(Option(e.getMessage).getOrElse(e.toString)))
      offset <- offsetRef.getAndUpdate(_ + chunk.length.toLong)
      entry   = ManifestEntry(offset, chunk.length, key)
      _      <- entriesRef.update(_ :+ entry)
    } yield key

  def materialize(
    manifest: Manifest,
    blockStore: BlockStore,
  ): IO[GravitonError, Bytes] =
    val stream = ZStream
      .fromIterable(manifest.entries)
      .mapZIO { entry =>
        blockStore
          .get(entry.block, None)
          .mapError(err => GravitonError.BackendUnavailable(Option(err.getMessage).getOrElse(err.toString)))
          .flatMap {
            case Some(bytes) => ZIO.succeed(bytes)
            case None        =>
              ZIO.fail(
                GravitonError.NotFound(s"missing block ${entry.block.hash.hex}")
              )
          }
      }
      .flatMap(identity)
    ZIO.succeed(Bytes(stream))
}
