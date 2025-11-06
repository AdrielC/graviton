package graviton
package ingest

import graviton.*
import graviton.BlockKey
import graviton.chunking.Chunker
import graviton.core.{BinaryAttributeKey, BinaryAttributes}
import graviton.core.model.Block
import graviton.Manifest
import zio.*
import zio.stream.*

import graviton.core.BlockStore
import graviton.core.model.FileSize
import graviton.core.model.Index
import graviton.core.BlockManifestEntry
import graviton.core.BlockManifest
import zio.prelude.NonEmptyMap

final case class IngestResult(
  blobKey: BlobKey,
  manifest: BlockManifest,
  attributes: BinaryAttributes,
  totalBlocks: Int,
)

trait FileIngestor:
  def ingest(
    bytes: Bytes,
    advertisedAttributes: BinaryAttributes = BinaryAttributes.empty,
    hashAlgorithm: HashAlgorithm = HashAlgorithm.SHA256,
  ): IO[GravitonError, IngestResult]

  def materialize(manifest: Manifest): IO[GravitonError, Bytes]

object FileIngestor:
  def ingest(
    bytes: Bytes,
    advertisedAttributes: BinaryAttributes = BinaryAttributes.empty,
    hashAlgorithm: HashAlgorithm = HashAlgorithm.SHA256,
  ): ZIO[FileIngestor, GravitonError, IngestResult] =
    ZIO.serviceWithZIO[FileIngestor](_.ingest(bytes, advertisedAttributes, hashAlgorithm))

  def materialize(manifest: Manifest): ZIO[FileIngestor, GravitonError, Bytes] =
    ZIO.serviceWithZIO[FileIngestor](_.materialize(manifest))

end FileIngestor

final case class FileIngestorLive(
  blockStore: BlockStore,
  chunker: Chunker,
) extends FileIngestor:

  private val IngestSource = "file-ingestor"

  def ingest(
    bytes: Bytes,
    advertisedAttributes: BinaryAttributes,
    hashAlgorithm: HashAlgorithm,
  ): IO[GravitonError, IngestResult] =
    ZIO.scoped:
      for {
        _          <- BinaryAttributes.validate(advertisedAttributes)
        hasher     <- Hashing
                        .hasher(hashAlgorithm)
                        .mapError(e => GravitonError.BackendUnavailable(Option(e.getMessage).getOrElse(e.toString)))
        sizeRef    <- Ref.make(Option.empty[FileSize])
        offsetRef  <- Ref.make[Index](Index.zero)
        entriesRef <- Ref.make(Chunk.empty[BlockManifestEntry])
        stream      = bytes
                        .mapError(e => GravitonError.BackendUnavailable(Option(e.getMessage).getOrElse(e.toString)))
                        .via(chunker.pipeline)
                        .tap { (block: Block) =>
                          hasher.update(block) *>
                            sizeRef.update(_.fold(Some(block.fileSize))(o => (block.fileSize ++ o)))
                        }
                        .mapError(e => GravitonError.BackendUnavailable(Option(e.getMessage).getOrElse(e.toString)))
                        .mapZIO(block => storeBlock(offsetRef, entriesRef)(block))
        blockCount <- stream
                        .runFold(0)((acc, _) => acc + 1)
                        .mapError(e => GravitonError.BackendUnavailable(Option(e.getMessage).getOrElse(e.toString)))
        digest     <- hasher.digest
        size       <- sizeRef.get
        totalSize  <- ZIO.fromOption(size).mapError(_ => GravitonError.PolicyViolation("file must have bytes"))
        manifest   <-
          entriesRef.get.map(entries => BlockManifest(totalSize, NonEmptyMap(hashAlgorithm -> digest), advertisedAttributes, entries))
        blobKey     = BlobKey(
                        hash = Hash(digest, hashAlgorithm),
                        algo = hashAlgorithm,
                        size = totalSize.value,
                        mediaTypeHint = advertisedAttributes
                          .getConfirmed(
                            BinaryAttributeKey.Server.contentType
                              .asInstanceOf[
                                BinaryAttributeKey.Aux[
                                  String,
                                  "contentType",
                                  "attr:server",
                                ]
                              ]
                          )
                          .map(_.value)
                          .orElse(
                            advertisedAttributes
                              .getAdvertised(
                                BinaryAttributeKey.Client.contentType
                                  .asInstanceOf[
                                    BinaryAttributeKey.Aux[
                                      String,
                                      "contentType",
                                      "attr:client",
                                    ]
                                  ]
                              )
                              .map(_.value)
                          ),
                      )
        attributes  = advertisedAttributes ++ BinaryAttributes.confirmed(
                        BinaryAttributeKey.Server
                          .selectDynamic[FileSize]("size")
                          .asInstanceOf[
                            BinaryAttributeKey.Aux[
                              FileSize,
                              "size",
                              "attr:server",
                            ]
                          ],
                        totalSize,
                        IngestSource,
                      )
      } yield IngestResult(blobKey, manifest, attributes, blockCount)

  private def storeBlock(
    offsetRef: Ref[Index],
    entriesRef: Ref[Chunk[BlockManifestEntry]],
  )(
    block: Block
  ): IO[GravitonError, BlockKey] =
    for {
      key    <- blockStore
                  .putBlock(block)
                  .mapError(e => GravitonError.BackendUnavailable(Option(e.getMessage).getOrElse(e.toString)))
      offset <- offsetRef.getAndUpdate(i => (i ++ Index.applyUnsafe(block.blockSize.value.toLong)).getOrElse(Index.zero))
      _      <- entriesRef.update(_ :+ BlockManifestEntry(offset, block.blockSize, key.toBinaryKey))
    } yield key

  def materialize(manifest: Manifest): IO[GravitonError, Bytes] =
    val stream = ZStream
      .fromIterable(manifest.entries)
      .mapZIO { entry =>
        blockStore
          .getBlock(BlockKey(entry.block.hash, entry.block.size), None)
          .mapError(err => GravitonError.BackendUnavailable(Option(err.getMessage).getOrElse(err.toString)))
          .flatMap {
            case Some(bytes) => ZIO.succeed(bytes)
            case None        =>
              ZIO.fail(
                GravitonError.NotFound(s"missing block ${entry.block.hash.hex}")
              )
          }
      }
      .mapError(e => GravitonError.BackendUnavailable(Option(e.getMessage).getOrElse(e.toString)))
    ZIO.succeed(Bytes(stream.flattenChunks))

object FileIngestorLive:
  val layer: URLayer[BlockStore & Chunker, FileIngestor] =
    ZLayer.fromFunction(FileIngestorLive.apply)
