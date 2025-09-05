package graviton.impl

import graviton.*
import graviton.chunking.FixedChunker
import graviton.core.{BinaryAttributeKey, BinaryAttributes}
import zio.*
import zio.stream.*

final class InMemoryFileStore private (
  blockStore: BlockStore,
  manifests: Ref[Map[FileKey, FileDescriptor]],
  detect: ContentTypeDetect,
) extends FileStore:

  def put(
    attrs: BinaryAttributes,
    blockSize: Int,
  ): ZSink[Any, Throwable, Byte, Nothing, FileKey] =
    ZSink.fromZIO(
      BinaryAttributes.validate(attrs).mapError(e => e: Throwable)
    ) *> {
      val algo    = HashAlgorithm.SHA256
      val hashing = Hashing.sink(algo)

      val blockCollect: ZSink[Any, Throwable, Chunk[
        Byte
      ], Nothing, (Vector[BlockKey], Long)] =
        ZSink.foldLeftZIO((Vector.empty[BlockKey], 0L)) { case ((acc, sz), chunk) =>
          ZStream.fromChunk(chunk).run(blockStore.put).map { key =>
            (acc :+ key, sz + key.size.toLong)
          }
        }

      val chunked: ZSink[Any, Throwable, Byte, Nothing, (Vector[BlockKey], Long)] =
        FixedChunker(blockSize).pipeline >>> blockCollect

      hashing.zipPar(chunked).mapZIO { case (hash, (keys, size)) =>
        val digest    = hash
        val fileBytes = Bytes(
          ZStream
            .fromIterable(keys)
            .mapZIO(b => blockStore.get(b).someOrFail(GravitonError.NotFound(b.hash.hex)))
            .flatMap(identity)
        )
        for
          detected    <- detect.detect(fileBytes)
          advertisedMt =
            attrs.getAdvertised(BinaryAttributeKey.contentType).map(_.value)
          _           <- advertisedMt match
                           case Some(mt) if detected.exists(_ != mt) =>
                             ZIO.fail(GravitonError.PolicyViolation("media type mismatch"))
                           case _                                    => ZIO.unit
          mediaType    = advertisedMt
                           .orElse(detected)
                           .getOrElse("application/octet-stream")
          fk           = FileKey(Hash(digest, algo), algo, size, mediaType)
          desc         = FileDescriptor(fk, Chunk.fromIterable(keys), blockSize)
          _           <- manifests.update(_ + (fk -> desc))
        yield fk
      }
    }

  def get(key: FileKey): IO[Throwable, Option[Bytes]] =
    describe(key).flatMap {
      case None     => ZIO.succeed(None)
      case Some(fd) =>
        val streams = fd.blocks.map(b => blockStore.get(b).someOrFail(GravitonError.NotFound(b.hash.hex)))
        ZIO
          .foreach(streams)(identity)
          .map(parts => Some(Bytes(parts.reduceLeft(_ ++ _))))
    }

  def describe(key: FileKey): IO[Throwable, Option[FileDescriptor]] =
    manifests.get.map(_.get(key))

  def list(selector: FileKeySelector): ZStream[Any, Throwable, FileDescriptor] =
    ZStream.fromZIO(manifests.get).flatMap { m =>
      ZStream.fromIterable(m.values)
    }

  def delete(key: FileKey): IO[Throwable, Boolean] =
    manifests
      .modify { m =>
        m.get(key) match
          case None     => (None, m)
          case Some(fd) => (Some(fd), m - key)
      }
      .flatMap {
        case None     => ZIO.succeed(false)
        case Some(fd) =>
          ZIO.foreachDiscard(fd.blocks)(blockStore.delete) *> ZIO.succeed(true)
      }

object InMemoryFileStore:
  def make(
    blockStore: BlockStore,
    detect: ContentTypeDetect,
  ): UIO[InMemoryFileStore] =
    Ref
      .make(Map.empty[FileKey, FileDescriptor])
      .map(new InMemoryFileStore(blockStore, _, detect))
