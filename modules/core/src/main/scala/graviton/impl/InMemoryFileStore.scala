package graviton.impl

import graviton.*
import zio.*
import zio.stream.*

final class InMemoryFileStore private (
    blockStore: BlockStore,
    manifests: Ref[Map[FileKey, FileDescriptor]]
) extends FileStore:

  def put(
      meta: FileMetadata,
      blockSize: Int
  ): ZSink[Any, Throwable, Byte, Nothing, FileKey] =
    ZSink.collectAll[Byte].mapZIO { data =>
      val full = Chunk.fromIterable(data)
      val blocks = Chunk
        .fromIterable(full.grouped(blockSize).map(Chunk.fromIterable).toList)
      for
        keys <- ZIO.foreach(blocks)(b =>
          ZStream.fromChunk(b).run(blockStore.put)
        )
        hash <- Hashing.compute(ZStream.fromChunk(full), HashAlgorithm.SHA256)
        algo = HashAlgorithm.SHA256
        size = full.length.toLong
        mediaType = meta.advertisedMediaType
          .getOrElse("application/octet-stream")
        fk = FileKey(Hash(hash, algo), algo, size, mediaType)
        desc = FileDescriptor(fk, Chunk.fromIterable(keys), blockSize)
        _ <- manifests.update(_ + (fk -> desc))
      yield fk
    }

  def get(key: FileKey): IO[Throwable, Option[Bytes]] =
    describe(key).flatMap {
      case None => ZIO.succeed(None)
      case Some(fd) =>
        val streams = fd.blocks.map(b =>
          blockStore.get(b).someOrFail(GravitonError.NotFound(b.hash.hex))
        )
        ZIO
          .foreach(streams)(identity)
          .map(parts => Some(parts.reduceLeft(_ ++ _)))
    }

  def describe(key: FileKey): IO[Throwable, Option[FileDescriptor]] =
    manifests.get.map(_.get(key))

  def list(selector: FileKeySelector): ZStream[Any, Throwable, FileDescriptor] =
    ZStream.fromZIO(manifests.get).flatMap { m =>
      ZStream.fromIterable(m.values)
    }

object InMemoryFileStore:
  def make(blockStore: BlockStore): UIO[InMemoryFileStore] =
    Ref
      .make(Map.empty[FileKey, FileDescriptor])
      .map(new InMemoryFileStore(blockStore, _))
