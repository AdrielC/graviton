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

import java.security.MessageDigest
import graviton.BlockStore
import graviton.core.model.FileSize
import graviton.core.model.Index
import graviton.core.BlockManifestEntry
import graviton.core.BlockManifest

import zio.prelude.NonEmptySortedMap


final case class IngestResult(
  blobKey: NonEmptySortedMap[HashAlgorithm, BlobKey],
  manifest: BlockManifest,
  attributes: BinaryAttributes,
  totalBlocks: Int,
)

trait FileIngestor:
  def ingest(
    bytes: Bytes,
    advertisedAttributes: BinaryAttributes = BinaryAttributes.empty,
    hashAlgorithm: NonEmptySortedMap[HashAlgorithm, Option[MessageDigest]] = NonEmptySortedMap(HashAlgorithm.default -> None),
    chunker: Option[Chunker] = None,
  ): IO[GravitonError, IngestResult]

  def materialize(manifest: Manifest): IO[GravitonError, Bytes]

object FileIngestor:
  def ingest(
    bytes: Bytes,
    advertisedAttributes: BinaryAttributes = BinaryAttributes.empty,
    hashAlgorithm: NonEmptySortedMap[HashAlgorithm, Option[MessageDigest]] = NonEmptySortedMap(HashAlgorithm.default -> None),
    chunker: Option[Chunker] = None,
  ): ZIO[FileIngestor, GravitonError, IngestResult] =
    ZIO.serviceWithZIO[FileIngestor](_.ingest(bytes, advertisedAttributes, hashAlgorithm, chunker))

  def materialize(manifest: Manifest): ZIO[FileIngestor, GravitonError, Bytes] =
    ZIO.serviceWithZIO[FileIngestor](_.materialize(manifest))

end FileIngestor

final case class FileIngestorLive(
  blockStore: BlockStore,
  _chunker: Chunker,
) extends FileIngestor:

  private val IngestSource                                  = "file-ingestor"
  private def backendFailure(err: Throwable): GravitonError =
    GravitonError.BackendUnavailable(Option(err.getMessage).getOrElse(err.toString), Some(err))

  def ingest(
    bytes: Bytes,
    advertisedAttributes: BinaryAttributes,
    hashAlgorithms: NonEmptySortedMap[HashAlgorithm, Option[MessageDigest]],
    chunker: Option[Chunker] = None,
  ): IO[GravitonError, IngestResult] =
    ZIO.scoped:
      for {
        _          <- BinaryAttributes.validate(advertisedAttributes)
        hasher     <- Hashing.hasher(hashAlgorithms).mapError(backendFailure)
        sizeRef    <- Ref.make(Option.empty[FileSize])
        offsetRef  <- Ref.make[Index](Index.zero)
        entriesRef <- Ref.make(Option.empty[NonEmptyChunk[BlockManifestEntry]])
        chunker    <- chunker.fold(ZIO.succeed(_chunker))(ZIO.succeed)
        stream      = bytes
                        .mapError(backendFailure)
                        .via(chunker.pipeline)
                        .mapZIO { (block: Block) =>
                          hasher
                            .update(block)
                            .mapError(backendFailure) *>
                            sizeRef
                              .update(_.fold(Some(block.fileSize))(o => (block.fileSize ++ o))) *>
                            storeBlock(offsetRef, entriesRef)(block)
                        }
        blockCount <- stream
                        .runFold(0)((acc, _) => acc + 1)
                        .mapError(backendFailure)
        digest     <- hasher.digest.mapError(backendFailure)
        size       <- sizeRef.get
        totalSize  <- ZIO.fromOption(size).mapError(_ => GravitonError.PolicyViolation("file must have bytes"))
        entries    <- entriesRef.get.flatMap(ZIO.fromOption).mapError(_ => GravitonError.PolicyViolation("no blocks ingested"))
        manifest   <- ZIO.succeed(BlockManifest(totalSize, digest, advertisedAttributes, entries))


        blobKey     = digest.map((algo, hash) => BlobKey(
                        hash = Hash.SingleHash(algo, hash),
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
                      ))
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
    entriesRef: Ref[Option[NonEmptyChunk[BlockManifestEntry]]],
  )(
    block: Block
  ): IO[GravitonError, NonEmptyChunk[BlockKey]] =
    for {
      key    <- blockStore
                  .putBlock(block)
                  .mapError(e => GravitonError.BackendUnavailable(Option(e.getMessage).getOrElse(e.toString)))
      offset <- offsetRef.getAndUpdate(i => (i ++ Index.applyUnsafe(block.blockSize.value.toLong)).getOrElse(Index.zero))
      out      <- entriesRef.updateAndGet(_.fold(Some(NonEmptyChunk((BlockManifestEntry(offset, block.blockSize, key.map(b => b.toBinaryKey))))))(
        entries => Some(entries :+ BlockManifestEntry(offset, block.blockSize, key.map(b => b.toBinaryKey))
        )))
    } yield out.get.flatMap(b => b.block.map(b => b: BlockKey))

  def materialize(manifest: Manifest): IO[GravitonError, Bytes] =
    val stream = ZStream
      .fromIterable(manifest.entries)
      .mapZIO { entry =>
        blockStore
          .get(BlockKey(entry.block.head.hash, entry.block.head.size), None)
          .mapError(err => GravitonError.BackendUnavailable(Option(err.getMessage).getOrElse(err.toString)))
          .flatMap {
            case Some(bytes) => ZIO.succeed(bytes)
            case None        =>
              ZIO.fail(
                GravitonError.NotFound(s"missing block ${entry.block.head.hash.hex}")
              )
          }
      }
      .mapError(e => GravitonError.BackendUnavailable(Option(e.getMessage).getOrElse(e.toString)))
    ZIO.succeed(stream.flattenBytes)

object FileIngestorLive:
  val layer: URLayer[BlockStore & Chunker, FileIngestorLive] =
    ZLayer.derive[FileIngestorLive]
