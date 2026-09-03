package graviton.runtime.stores

import graviton.core.locator.BlobLocator
import zio.*
import zio.stream.{ZSink, ZStream}

import java.nio.ByteBuffer
import java.nio.channels.{Channels, FileChannel}
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption, StandardOpenOption}
import java.util.concurrent.atomic.AtomicBoolean

/** Atomic filesystem staging objects for resumable upload parts. */
final class FsMutableObjectStore(
  root: Path,
  scheme: String = "file",
  bucket: String = "graviton-staging",
) extends MutableObjectStore:
  private val base = root.resolve("cas/upload-staging").toAbsolutePath.normalize()

  override def put(locator: BlobLocator): ZSink[Any, StoreError, Byte, Nothing, Unit] =
    ZSink.unwrapScoped {
      (for
        destination <- pathFor(locator)
        writer      <- ZIO.acquireRelease(FsMutableObjectStore.Writer.open(destination))(writer =>
                         graviton.runtime.lifecycle.ResourceFinalizer.run("filesystem mutable-object writer")(writer.closeAndDelete)
                       )
      yield ZSink
        .foldLeftChunksZIO[Any, Throwable, Byte, FsMutableObjectStore.Writer](writer)((current, chunk) => current.write(chunk).as(current))
        .mapZIO(_.commit)
        .ignoreLeftover
        .mapError(storeError(StoreOperation.PutObject))).mapError(storeError(StoreOperation.PutObject))
    }

  override def delete(locator: BlobLocator): IO[StoreError, Unit] =
    pathFor(locator)
      .flatMap(path => ZIO.attemptBlocking(Files.deleteIfExists(path)).unit)
      .mapError(storeError(StoreOperation.DeleteObject))

  override def copy(src: BlobLocator, dest: BlobLocator): IO[StoreError, Unit] =
    (for
      source      <- pathFor(src)
      destination <- pathFor(dest)
      _           <- ZIO.attemptBlocking {
                       Files.createDirectories(destination.getParent)
                       val tmp = Files.createTempFile(destination.getParent, ".copy-", ".tmp")
                       try
                         Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING)
                         forceFile(tmp)
                         Files.move(tmp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                         forceDirectory(destination.getParent)
                       finally
                         val _ = Files.deleteIfExists(tmp)
                     }
    yield ()).mapError(storeError(StoreOperation.CopyObject))

  override def head(locator: BlobLocator): IO[StoreError, Option[Long]] =
    pathFor(locator)
      .flatMap { path =>
        ZIO.attemptBlocking {
          if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then Some(Files.size(path)) else None
        }
      }
      .mapError(storeError(StoreOperation.HeadObject))

  override def list(prefix: String): ZStream[Any, StoreError, BlobLocator] =
    val normalized = prefix.trim.stripPrefix("/")
    val start      = base.resolve(normalized).normalize()
    if !start.startsWith(base) then ZStream.fail(StoreError.InvalidInput(StoreOperation.ListObjects, "staging prefix escapes its root"))
    else
      FsBlobManifestRepo
        .walkFiles(start)(path => Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        .mapZIO { path =>
          ZIO
            .fromEither(BlobLocator.from(scheme, bucket, base.relativize(path).toString.replace(java.io.File.separatorChar, '/')))
            .mapError(new IllegalStateException(_))
        }
        .mapError(storeError(StoreOperation.ListObjects))

  override def get(locator: BlobLocator): ZStream[Any, StoreError, Byte] =
    ZStream
      .fromZIO(pathFor(locator))
      .flatMap { path =>
        ZStream
          .acquireReleaseWith(
            ZIO.attemptBlocking(Channels.newInputStream(Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))
          )(stream => graviton.runtime.lifecycle.ResourceFinalizer.closeBlocking("filesystem object stream")(stream.close()))
          .flatMap(stream => ZStream.fromInputStream(stream, chunkSize = 64 * 1024))
      }
      .mapError(storeError(StoreOperation.GetObject))

  private def pathFor(locator: BlobLocator): Task[Path] =
    if locator.scheme.value != scheme then
      ZIO.fail(new IllegalArgumentException(s"Expected '$scheme' staging locator, received '${locator.scheme.value}'"))
    else if locator.bucket.value != bucket then
      ZIO.fail(new IllegalArgumentException(s"Expected '$bucket' staging bucket, received '${locator.bucket.value}'"))
    else
      val resolved = base.resolve(locator.path.value).normalize()
      if resolved.startsWith(base) then ZIO.succeed(resolved)
      else ZIO.fail(new IllegalArgumentException("staging locator escapes its root"))

  private def storeError(operation: StoreOperation)(error: Throwable): StoreError =
    StoreError.fromThrowable(operation, StoreBackend.Filesystem)(error)

  private def forceFile(path: Path): Unit =
    val channel = FileChannel.open(path, StandardOpenOption.WRITE)
    try channel.force(true)
    finally channel.close()

  private def forceDirectory(path: Path): Unit =
    val channel = FileChannel.open(path, StandardOpenOption.READ)
    try channel.force(true)
    finally channel.close()

object FsMutableObjectStore:
  private final class Writer private (
    destination: Path,
    tmp: Path,
    channel: FileChannel,
    closed: AtomicBoolean,
  ):
    def write(chunk: Chunk[Byte]): Task[Unit] =
      ZIO.attemptBlocking {
        val buffer = ByteBuffer.wrap(chunk.toArray)
        while buffer.hasRemaining do
          val _ = channel.write(buffer)
      }

    def commit: Task[Unit] =
      ZIO.attemptBlocking {
        close(force = true)
        Files.move(tmp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        forceDirectory(destination.getParent)
      }

    def closeAndDelete: Task[Unit] =
      ZIO.attemptBlocking {
        close(force = false)
        val _ = Files.deleteIfExists(tmp)
      }

    private def close(force: Boolean): Unit =
      if closed.compareAndSet(false, true) then
        try if force then channel.force(true)
        finally channel.close()

  private object Writer:
    def open(destination: Path): Task[Writer] =
      ZIO.attemptBlocking {
        Files.createDirectories(destination.getParent)
        val tmp     = Files.createTempFile(destination.getParent, ".upload-", ".tmp")
        val channel = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        new Writer(destination, tmp, channel, new AtomicBoolean(false))
      }

  private def forceDirectory(path: Path): Unit =
    val channel = FileChannel.open(path, StandardOpenOption.READ)
    try channel.force(true)
    finally channel.close()
