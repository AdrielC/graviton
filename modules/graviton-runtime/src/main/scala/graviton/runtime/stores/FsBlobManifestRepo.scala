package graviton.runtime.stores

import graviton.core.bytes.{Digest, HashAlgo}
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.manifest.{FramedManifest, Manifest, ManifestEntry}
import graviton.core.types.FileSize
import graviton.runtime.streaming.BlobStreamer
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.MaxLength
import zio.*
import zio.stream.ZStream

import java.nio.file.attribute.FileTime
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption}
import java.time.Instant
import scala.jdk.CollectionConverters.*

/**
 * Durable, filesystem-backed manifest repository.
 *
 * New manifests use the incremental `GVM2` format and are written via a
 * temporary file plus atomic rename. Legacy `FramedManifest` version 1 files
 * remain readable. The file's modification time records the ingestion timestamp
 * returned by [[BlobStore.stat]].
 *
 * Layout:
 *   `<root>/<prefix>/<algo>/<digest>-<size>.manifest`
 */
final class FsBlobManifestRepo(
  root: Path,
  prefix: String = "cas/manifests",
) extends BlobManifestRepo:

  override def put(
    blob: BinaryKey.Blob,
    manifest: Manifest,
    ingestedAt: Instant,
  ): ZIO[Any, Throwable, Unit] =
    for
      frame <- ZIO
                 .fromEither(FramedManifest.encode(manifest))
                 .mapError(message => new IllegalArgumentException(s"Cannot encode manifest: $message"))
      _     <- writeAtomically(pathFor(blob), frame.bytes, ingestedAt)
    yield ()

  override def putStream(
    blob: BinaryKey.Blob,
    totalSize: FileSize,
    blockCount: Int,
    entries: ZStream[Any, Throwable, ManifestEntry],
    ingestedAt: Instant,
  ): ZIO[Any, Throwable, Unit] =
    val path = pathFor(blob)
    ZIO
      .fromEither(BlobManifestRepo.validateStreamArguments(blob, totalSize, blockCount))
      .mapError(new IllegalArgumentException(_)) *>
      ZIO.scoped {
        for
          tmp    <- ZIO.attemptBlocking {
                      Files.createDirectories(path.getParent)
                      Files.createTempFile(path.getParent, ".manifest-", ".tmp")
                    }
          _      <- ZIO.addFinalizer(ZIO.attemptBlocking(Files.deleteIfExists(tmp)).ignore)
          writer <- ZIO.acquireRelease(
                      ZIO.attemptBlocking(
                        StreamingManifestFile.Writer.open(
                          tmp,
                          StreamingManifestFile.Header(totalSize, blockCount),
                        )
                      )
                    )(current => ZIO.attemptBlocking(current.close()).orDie)
          _      <- entries
                      .rechunk(FsBlobManifestRepo.WriteBatchEntries)
                      .chunks
                      .runForeach(batch => ZIO.attemptBlocking(writer.writeBatch(batch)))
          _      <- ZIO.attemptBlocking(writer.finish())
          _      <- ZIO.attemptBlocking(writer.close())
          _      <- commitStreamingManifest(tmp, path, ingestedAt)
        yield ()
      }

  override def get(blob: BinaryKey.Blob): ZIO[Any, Throwable, Option[StoredManifest]] =
    val path = pathFor(blob)
    ZIO.attemptBlocking(Files.exists(path, LinkOption.NOFOLLOW_LINKS)).flatMap {
      case false => ZIO.none
      case true  =>
        StreamingManifestFile.isStreaming(path).flatMap {
          case true  => readStreamingManifest(path).map(Some(_))
          case false => ZIO.attemptBlocking(Some(readLegacyManifest(path)))
        }
    }

  override def getSummary(blob: BinaryKey.Blob): ZIO[Any, Throwable, Option[StoredManifestSummary]] =
    val path = pathFor(blob)
    ZIO.attemptBlocking(Files.exists(path, LinkOption.NOFOLLOW_LINKS)).flatMap {
      case false => ZIO.none
      case true  => readSummary(path).map(Some(_))
    }

  override def list: ZIO[Any, Throwable, Chunk[(BinaryKey.Blob, StoredManifest)]] =
    manifestKeys.flatMap(keys =>
      ZIO
        .foreach(keys)(blob => get(blob).map(_.map(blob -> _)))
        .map(values =>
          Chunk.fromIterable(
            values.flatten.sortBy { case (_, stored) => stored.ingestedAt }(using Ordering[Instant].reverse)
          )
        )
    )

  override def listSummaries: ZIO[Any, Throwable, Chunk[(BinaryKey.Blob, StoredManifestSummary)]] =
    manifestKeys.flatMap(keys =>
      ZIO
        .foreach(keys)(blob => getSummary(blob).map(_.map(blob -> _)))
        .map(values =>
          Chunk.fromIterable(
            values.flatten.sortBy { case (_, stored) => stored.ingestedAt }(using Ordering[Instant].reverse)
          )
        )
    )

  /**
   * Directory-backed maintenance cursor. Unlike [[listSummaries]], this keeps
   * only the active walk entry and one manifest header in memory. The walk has
   * no global ordering because obtaining newest-first order would require
   * materializing the repository inventory.
   */
  override def streamSummaries: ZStream[Any, Throwable, (BinaryKey.Blob, StoredManifestSummary)] =
    manifestPaths.mapZIO { path =>
      for
        blob    <- ZIO.fromEither(keyFromPath(path)).mapError(message => new IllegalArgumentException(message))
        summary <- readSummary(path)
      yield blob -> summary
    }

  override def streamBlockRefs(blob: BinaryKey.Blob): ZStream[Any, Throwable, BlobStreamer.BlockRef] =
    val path = pathFor(blob)
    ZStream.unwrap {
      ZIO.attemptBlocking(Files.exists(path, LinkOption.NOFOLLOW_LINKS)).flatMap {
        case false => ZIO.fail(new NoSuchElementException(s"Missing manifest for ${blob.bits.render}"))
        case true  =>
          StreamingManifestFile.isStreaming(path).map {
            case true  =>
              StreamingManifestFile.streamEntries(path).zipWithIndex.map { case (entry, index) =>
                entry.key match
                  case block: BinaryKey.Block => BlobStreamer.BlockRef(index, block)
                  case other                  =>
                    throw new IllegalArgumentException(
                      s"CAS manifest entry $index must reference a block key, got $other"
                    )
              }
            case false =>
              ZStream.fromZIO(get(blob)).flatMap {
                case None         => ZStream.fail(new NoSuchElementException(s"Missing manifest for ${blob.bits.render}"))
                case Some(stored) =>
                  ZStream.fromIterable(
                    stored.manifest.entries.zipWithIndex.collect { case (ManifestEntry(block: BinaryKey.Block, _, _), index) =>
                      BlobStreamer.BlockRef(index.toLong, block)
                    }
                  )
              }
          }
      }
    }

  override def delete(blob: BinaryKey.Blob): ZIO[Any, Throwable, Boolean] =
    ZIO.attemptBlocking(Files.deleteIfExists(pathFor(blob)))

  override def healthCheck: ZIO[Any, Throwable, Unit] =
    ZIO.attemptBlocking {
      val directory = root.resolve(prefix)
      Files.createDirectories(directory)
      val probe     = Files.createTempFile(directory, ".ready-", ".tmp")
      try
        val channel = FileChannel.open(probe, StandardOpenOption.WRITE)
        try channel.force(true)
        finally channel.close()
      finally
        val _ = Files.deleteIfExists(probe)
      ()
    }

  private[stores] def pathFor(blob: BinaryKey.Blob): Path =
    val algo = blob.bits.algo.primaryName.toLowerCase.replace("-", "")
    val name = s"${blob.bits.digest.hex.value}-${blob.bits.size}.manifest"
    root.resolve(prefix).resolve(algo).resolve(name)

  private def readLegacyManifest(path: Path): StoredManifest =
    if !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then
      throw new IllegalStateException(s"Manifest path is not a regular file: $path")

    val size = Files.size(path)
    if size > FsBlobManifestRepo.MaxManifestBytes then
      throw new IllegalArgumentException(
        s"Manifest exceeds ${FsBlobManifestRepo.MaxManifestBytes} byte safety limit: $path"
      )

    val frame      = FramedManifest.Frame(readBoundedManifest(path))
    val manifest   = FramedManifest
      .decode(frame)
      .fold(message => throw new IllegalArgumentException(s"Invalid manifest at $path: $message"), identity)
    val ingestedAt = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant
    StoredManifest(manifest, ingestedAt)

  private def readStreamingManifest(path: Path): Task[StoredManifest] =
    for
      header     <- StreamingManifestFile.readHeader(path)
      _          <-
        ZIO
          .fail(
            new IllegalArgumentException(
              s"Manifest has ${header.blockCount} entries; materialized inspection is limited to ${FsBlobManifestRepo.MaxMaterializedEntries}. Use streamBlockRefs for reconstruction."
            )
          )
          .when(header.blockCount > FsBlobManifestRepo.MaxMaterializedEntries)
      entries    <- StreamingManifestFile.streamEntries(path).runFold(List.empty[ManifestEntry])((reversed, entry) => entry :: reversed)
      manifest   <- ZIO
                      .fromEither(Manifest.fromEntries(entries.reverse))
                      .mapError(message => new IllegalArgumentException(message))
      ingestedAt <- ZIO.attemptBlocking(Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant)
    yield StoredManifest(manifest, ingestedAt)

  private def readSummary(path: Path): Task[StoredManifestSummary] =
    StreamingManifestFile.isStreaming(path).flatMap {
      case true  =>
        for
          header     <- StreamingManifestFile.readHeader(path)
          ingestedAt <- ZIO.attemptBlocking(Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant)
        yield StoredManifestSummary(header.totalSize, header.blockCount, ingestedAt)
      case false =>
        ZIO.attemptBlocking {
          val stored = readLegacyManifest(path)
          StoredManifestSummary(FileSize.unsafe(stored.manifest.size), stored.manifest.entries.length, stored.ingestedAt)
        }
    }

  private def manifestPaths: ZStream[Any, Throwable, Path] =
    FsBlobManifestRepo.walkFiles(root.resolve(prefix)) { path =>
      Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.getFileName.toString.endsWith(".manifest")
    }

  private def manifestKeys: Task[Chunk[BinaryKey.Blob]] =
    ZIO.attemptBlocking {
      val manifestsRoot = root.resolve(prefix)
      if !Files.exists(manifestsRoot, LinkOption.NOFOLLOW_LINKS) then Chunk.empty
      else if !Files.isDirectory(manifestsRoot, LinkOption.NOFOLLOW_LINKS) then
        throw new IllegalStateException(s"Manifest root is not a directory: $manifestsRoot")
      else
        val paths = Files.walk(manifestsRoot)
        try
          Chunk.fromIterable(
            paths
              .iterator()
              .asScala
              .filter(path => path.getFileName.toString.endsWith(".manifest"))
              .filter(path => Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
              .map(path => keyFromPath(path).fold(message => throw new IllegalArgumentException(message), identity))
              .toVector
          )
        finally paths.close()
    }

  private def readBoundedManifest(path: Path): FsBlobManifestRepo.ManifestBytes =
    val input = Files.newInputStream(path, StandardOpenOption.READ)
    try
      val bytes = input.readNBytes(FsBlobManifestRepo.MaxManifestBytes + 1)
      if bytes.length > FsBlobManifestRepo.MaxManifestBytes then
        throw new IllegalArgumentException(
          s"Manifest exceeds ${FsBlobManifestRepo.MaxManifestBytes} byte safety limit: $path"
        )
      bytes
        .refineEither[MaxLength[67108864]]
        .fold(message => throw new IllegalArgumentException(message), identity)
    finally input.close()

  private def keyFromPath(path: Path): Either[String, BinaryKey.Blob] =
    val algorithmDirectory = Option(path.getParent).flatMap(parent => Option(parent.getFileName)).map(_.toString).getOrElse("")
    val fileName           = path.getFileName.toString.stripSuffix(".manifest")
    val sizeSeparator      = fileName.lastIndexOf('-')

    for
      _      <- Either.cond(sizeSeparator > 0, (), s"Invalid manifest filename: $path")
      digest <- Digest.fromString(fileName.substring(0, sizeSeparator))
      size   <- fileName.substring(sizeSeparator + 1).toLongOption.toRight(s"Invalid manifest byte length: $path")
      algo   <- HashAlgo.values
                  .find(_.primaryName.toLowerCase.replace("-", "") == algorithmDirectory.toLowerCase)
                  .toRight(s"Unsupported manifest algorithm directory: $algorithmDirectory")
      bits   <- KeyBits.create(algo, digest, size)
      blob   <- BinaryKey.blob(bits)
    yield blob

  private def writeAtomically(path: Path, bytes: Array[Byte], ingestedAt: Instant): Task[Unit] =
    ZIO.attemptBlocking {
      Files.createDirectories(path.getParent)
      val tmp = Files.createTempFile(path.getParent, ".manifest-", ".tmp")
      try
        val tmpChannel       = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        try
          tmpChannel.write(java.nio.ByteBuffer.wrap(bytes))
          tmpChannel.force(true)
        finally tmpChannel.close()
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Files.setLastModifiedTime(path, FileTime.from(ingestedAt))
        val fileChannel      = FileChannel.open(path, StandardOpenOption.READ)
        try fileChannel.force(true)
        finally fileChannel.close()
        val directoryChannel = FileChannel.open(path.getParent, StandardOpenOption.READ)
        try directoryChannel.force(true)
        finally directoryChannel.close()
        ()
      finally
        try
          val _ = Files.deleteIfExists(tmp)
          ()
        catch case _: java.io.IOException => ()
    }

  private def commitStreamingManifest(tmp: Path, path: Path, ingestedAt: Instant): Task[Unit] =
    ZIO.attemptBlocking {
      val fileChannel      = FileChannel.open(tmp, StandardOpenOption.WRITE)
      try fileChannel.force(true)
      finally fileChannel.close()
      Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      Files.setLastModifiedTime(path, FileTime.from(ingestedAt))
      val directoryChannel = FileChannel.open(path.getParent, StandardOpenOption.READ)
      try directoryChannel.force(true)
      finally directoryChannel.close()
    }

object FsBlobManifestRepo:
  type ManifestBytes = Array[Byte] :| MaxLength[67108864]

  val MaxManifestBytes: Int       = 64 * 1024 * 1024
  val MaxMaterializedEntries: Int = BlobManifestRepo.MaxMaterializedEntries
  private val WriteBatchEntries   = 512

  private final class FileWalker(
    paths: java.util.stream.Stream[Path],
    iterator: java.util.Iterator[Path],
    include: Path => Boolean,
  ):
    def next(): Option[Path] =
      var found: Option[Path] = None
      while found.isEmpty && iterator.hasNext do
        val path = iterator.next()
        if include(path) then found = Some(path)
      found

    def close(): Unit = paths.close()

  /** Lazily walk regular files and close the JVM directory stream on interruption. */
  private[stores] def walkFiles(root: Path)(include: Path => Boolean): ZStream[Any, Throwable, Path] =
    ZStream.unwrap {
      ZIO
        .attemptBlocking {
          if !Files.exists(root, LinkOption.NOFOLLOW_LINKS) then None
          else if !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) then
            throw new IllegalStateException(s"Manifest root is not a directory: $root")
          else Some(())
        }
        .map {
          case None    => ZStream.empty
          case Some(_) =>
            ZStream
              .acquireReleaseWith(
                ZIO.attemptBlocking {
                  val paths = Files.walk(root)
                  new FileWalker(paths, paths.iterator(), include)
                }
              )(walker => ZIO.attemptBlocking(walker.close()).orDie)
              .flatMap(walker => ZStream.unfoldZIO(walker)(current => ZIO.attemptBlocking(current.next().map(_ -> current))))
        }
    }

  def layer(root: Path, prefix: String = "cas/manifests"): ULayer[BlobManifestRepo] =
    ZLayer.succeed(new FsBlobManifestRepo(root, prefix))
