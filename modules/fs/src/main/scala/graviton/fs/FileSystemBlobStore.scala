package graviton.fs

import graviton.*
import zio.*
import zio.stream.*
import java.nio.file.{Files, Path, StandardOpenOption}

/** Simple BlobStore backed by the local filesystem. Blocks are stored under
  * <root>/<hash-hex>. No reference counting or pruning is performed.
  */
final class FileSystemBlobStore private (root: Path, val id: BlobStoreId)
    extends BlobStore:
  private def pathFor(key: BlockKey): Path =
    root.resolve(key.hash.hex)

  def status: UIO[BlobStoreStatus] = ZIO.succeed(BlobStoreStatus.Operational)

  def read(key: BlockKey): IO[Throwable, Option[Bytes]] =
    ZIO.attempt(Files.exists(pathFor(key))).flatMap { exists =>
      if !exists then ZIO.succeed(None)
      else ZIO.succeed(Some(Bytes(ZStream.fromPath(pathFor(key)))))
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
                StandardOpenOption.TRUNCATE_EXISTING
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
