package graviton.impl

import graviton.*
import graviton.chunking.FixedChunker
import zio.*
import zio.stream.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import graviton.Logging

final class InMemoryFileStore private (
    blockStore: BlockStore,
    manifests: Ref[Map[FileKey, FileDescriptor]],
    detect: ContentTypeDetect
) extends FileStore:

  def put(
      meta: FileMetadata,
      blockSize: Int
  ): ZSink[Any, Throwable, Byte, Nothing, FileKey] =
    ZSink.unwrap {
      Logging.withCorrelation("FileStore.put") {
        val algo = HashAlgorithm.SHA256
        val hashing = Hashing.sink(algo)

        val blockCollect: ZSink[Any, Throwable, Chunk[
          Byte
        ], Nothing, (Vector[BlockKey], Long)] =
          ZSink.foldLeftZIO((Vector.empty[BlockKey], 0L)) {
            case ((acc, sz), chunk) =>
              ZStream.fromChunk(chunk).run(blockStore.put).map { key =>
                (acc :+ key, sz + key.size.toLong)
              }
          }

        val chunked
            : ZSink[Any, Throwable, Byte, Nothing, (Vector[BlockKey], Long)] =
          FixedChunker(blockSize).pipeline >>> blockCollect

        ZIO.succeed {
          hashing.zipPar(chunked).mapZIO { case (hash, (keys, size)) =>
            val digest = hash.assume[MinLength[16] & MaxLength[64]]
            val sizeR = size.assume[GreaterEqual[0]]
            val fileBytes = Bytes(
              ZStream
                .fromIterable(keys)
                .mapZIO(b =>
                  blockStore
                    .get(b)
                    .someOrFail(GravitonError.NotFound(b.hash.hex))
                )
                .flatMap(identity)
            )
            for
              detected <- detect.detect(fileBytes)
              _ <- meta.advertisedMediaType match
                case Some(mt) if detected.exists(_ != mt) =>
                  ZIO.fail(GravitonError.PolicyViolation("media type mismatch"))
                case _ => ZIO.unit
              mediaType = meta.advertisedMediaType
                .orElse(detected)
                .getOrElse("application/octet-stream")
              fk = FileKey(Hash(digest, algo), algo, sizeR, mediaType)
              desc = FileDescriptor(fk, Chunk.fromIterable(keys), blockSize)
              _ <- manifests.update(_ + (fk -> desc))
            yield fk
          }
        }
      }
    }

  def get(key: FileKey): IO[Throwable, Option[Bytes]] =
    Logging.withCorrelation("FileStore.get") {
      describe(key).flatMap {
        case None => ZIO.succeed(None)
        case Some(fd) =>
          val streams = fd.blocks.map(b =>
            blockStore.get(b).someOrFail(GravitonError.NotFound(b.hash.hex))
          )
          ZIO
            .foreach(streams)(identity)
            .map(parts => Some(Bytes(parts.reduceLeft(_ ++ _))))
      }
    }

  def describe(key: FileKey): IO[Throwable, Option[FileDescriptor]] =
    Logging.withCorrelation("FileStore.describe") {
      manifests.get.map(_.get(key))
    }

  def list(selector: FileKeySelector): ZStream[Any, Throwable, FileDescriptor] =
    ZStream
      .fromZIO(Logging.withCorrelation("FileStore.list")(manifests.get))
      .flatMap { m =>
        ZStream.fromIterable(m.values)
      }

object InMemoryFileStore:
  def make(
      blockStore: BlockStore,
      detect: ContentTypeDetect
  ): UIO[InMemoryFileStore] =
    Ref
      .make(Map.empty[FileKey, FileDescriptor])
      .map(new InMemoryFileStore(blockStore, _, detect))
