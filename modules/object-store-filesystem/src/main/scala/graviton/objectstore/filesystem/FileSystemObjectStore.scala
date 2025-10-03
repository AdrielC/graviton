package graviton.objectstore.filesystem

import graviton.objectstore.*
import graviton.ranges.ByteRange
import _root_.zio.*
import _root_.zio.stream.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import scala.util.Try

final class FileSystemObjectStore private (root: Path) extends ObjectStore:
  private def resolve(path: ObjectPath): Path = root.resolve(path.asString)

  def head(path: ObjectPath): IO[ObjectStoreError, Option[ObjectMetadata]] =
    val target = resolve(path)
    ZIO.attempt(Files.exists(target)).mapError(ObjectStoreError.fromThrowable).flatMap { exists =>
      if !exists then ZIO.succeed(None)
      else
        ZIO
          .attemptBlockingIO(Files.readAttributes(target, classOf[BasicFileAttributes]))
          .mapError(ObjectStoreError.fromThrowable)
          .map { attrs =>
            val modified = Option(attrs.lastModifiedTime()).map(_.toInstant)
            Some(ObjectMetadata(attrs.size(), None, modified, None, Map.empty))
          }
    }

  def list(prefix: ObjectPath, recursive: Boolean): ZStream[Any, ObjectStoreError, ListedObject] =
    val start = resolve(prefix)
    val depth = if recursive then Int.MaxValue else 1
    ZStream
      .fromZIO(ZIO.attempt(Files.exists(start)).mapError(ObjectStoreError.fromThrowable))
      .flatMap { exists =>
        if !exists then ZStream.empty
        else
          ZStream
            .fromJavaStreamScoped(ZIO.attempt(Files.walk(start, depth)).mapError(ObjectStoreError.fromThrowable))
            .mapError(ObjectStoreError.fromThrowable)
            .filterNot(_.equals(start))
            .mapZIO { child =>
              ZIO
                .attempt {
                  val relative = root.relativize(child)
                  val name     = relative.toString.replace('\\', '/')
                  val attrs    = Files.readAttributes(child, classOf[BasicFileAttributes])
                  val isDir    = attrs.isDirectory
                  ListedObject(ObjectPath(if name.isEmpty then "" else name + (if isDir then "/" else "")), attrs.size(), isDir)
                }
                .mapError(ObjectStoreError.fromThrowable)
            }
      }

  def get(path: ObjectPath, range: Option[ByteRange]): ZStream[Any, ObjectStoreError, Byte] =
    val target = resolve(path)
    val base   = ZStream.fromPath(target).mapError(ObjectStoreError.fromThrowable)
    range match
      case None     => base
      case Some(br) =>
        val startEither  = Try(Math.toIntExact(br.startValue)).toEither
        val lengthEither = Try(Math.toIntExact(br.lengthValue)).toEither
        (startEither, lengthEither) match
          case (Right(start), Right(length)) => base.drop(start).take(length.toLong)
          case _                             =>
            ZStream.fail(
              ObjectStoreError.Unexpected("byte range exceeds JVM Int range for filesystem backend", None)
            )

  def put(path: ObjectPath, data: ZStream[Any, Throwable, Byte], metadata: PutMetadata): IO[ObjectStoreError, Unit] =
    val target = resolve(path)
    ZIO
      .attempt(Option(target.getParent).foreach(path => Files.createDirectories(path)))
      .mapError(ObjectStoreError.fromThrowable) *> ZIO.scoped {
      ZIO
        .fromAutoCloseable(
          ZIO
            .attempt(
              Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            )
            .mapError(ObjectStoreError.fromThrowable)
        )
        .flatMap { os =>
          data
            .run(ZSink.fromOutputStream(os))
            .mapError(ObjectStoreError.fromThrowable)
            .unit
        }
    }

  def putMultipart(path: ObjectPath, parts: ZStream[Any, Throwable, Byte], metadata: PutMetadata): IO[ObjectStoreError, Unit] =
    // For filesystem backend multipart simply delegates to put.
    put(path, parts, metadata)

  def delete(path: ObjectPath): IO[ObjectStoreError, Boolean] =
    val target = resolve(path)
    ZIO.attempt(Files.deleteIfExists(target)).mapError(ObjectStoreError.fromThrowable)

  def copy(from: ObjectPath, to: ObjectPath, options: CopyOptions): IO[ObjectStoreError, Unit] =
    val src  = resolve(from)
    val dest = resolve(to)
    ZIO
      .attempt(Option(dest.getParent).foreach(path => Files.createDirectories(path)))
      .mapError(ObjectStoreError.fromThrowable) *>
      ZIO
        .attempt {
          val copyOpts =
            if options.overwrite then Array[CopyOption](StandardCopyOption.REPLACE_EXISTING)
            else Array.empty[CopyOption]
          Files.copy(src, dest, copyOpts*)
        }
        .unit
        .mapError(ObjectStoreError.fromThrowable)

object FileSystemObjectStore:
  def make(root: Path): Task[FileSystemObjectStore] =
    ZIO.attempt(new FileSystemObjectStore(root))
