package graviton.fs

import graviton.*
import zio.*
import zio.stream.*
import java.nio.file.{Files, Path, StandardOpenOption}

/**
 * Simple BlobStore backed by the local filesystem. Blocks are stored under
 * <root>/<hash-hex>. No reference counting or pruning is performed.
 */
final class FileSystemBlobStore private (root: Path, val id: BlobStoreId) extends BlobStore:
  private def pathFor(key: BlockKey): Path =
    root.resolve(key.hash.hex)

  def status: UIO[BlobStoreStatus] = ZIO.succeed(BlobStoreStatus.Operational)

  def read(
    key: BlockKey,
    range: Option[ByteRange] = None,
  ): IO[Throwable, Option[Bytes]] =
    ZIO.attempt(Files.exists(pathFor(key))).flatMap { exists =>
      if !exists then ZIO.succeed(None)
      else
        val p = pathFor(key)
        ZIO.scoped {
          ZIO
            .fromAutoCloseable(ZIO.attempt(Files.newByteChannel(p)))
            .flatMap { ch =>
              val size         = ch.size()
              val (start, end) =
                range.map(r => (r.start, r.endExclusive)).getOrElse((0L, size))
              val len          = (end - start).toInt
              val buf          = java.nio.ByteBuffer.allocate(len)
              ZIO.attempt {
                ch.position(start)
                var read = 0
                while read < len do
                  val n = ch.read(buf)
                  if n < 0 then throw new java.io.EOFException()
                  read += n
                buf.flip()
                Some(Bytes(ZStream.fromChunk(zio.Chunk.fromByteBuffer(buf))))
              }
            }
        }
    }

  def write(key: BlockKey, data: Bytes): IO[Throwable, Unit] =
    val p = pathFor(key)
    for
      _ <- ZIO.attempt(Files.createDirectories(p.getParent))
      _ <- ZIO.scoped {
             ZIO
               .fromAutoCloseable(
                 ZIO.attempt(
                   Files.newOutputStream(
                     p,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING,
                   )
                 )
               )
               .flatMap(os => data.run(ZSink.fromOutputStream(os)))
           }
    yield ()

  def delete(key: BlockKey): IO[Throwable, Boolean] =
    ZIO.attempt(Files.deleteIfExists(pathFor(key)))

object FileSystemBlobStore:
  def make(root: Path, id: String = "fs"): UIO[FileSystemBlobStore] =
    ZIO.succeed(new FileSystemBlobStore(root, BlobStoreId(id)))
