package graviton.runtime.stores

import graviton.core.bytes.{Digest, HashAlgo}
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.manifest.{Manifest, ManifestEntry}
import graviton.core.types.FileSize
import graviton.runtime.model.{InventoryCursor, InventoryNamespace, InventoryPage, InventoryPageSize}
import graviton.runtime.streaming.BlobStreamer
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
 * Manifests use the incremental `GVM2` format and are written via a temporary
 * file plus atomic rename. The file's modification time records the ingestion
 * timestamp returned by [[BlobStore.stat]].
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
  ): IO[StoreError, Unit] =
    for
      size <- ZIO
                .fromEither(FileSize.either(manifest.size))
                .mapError(StoreError.InvalidInput(StoreOperation.PutManifest, _))
      _    <- putStream(blob, size, manifest.entries.length, ZStream.fromIterable(manifest.entries), ingestedAt)
    yield ()

  override def putStream(
    blob: BinaryKey.Blob,
    totalSize: FileSize,
    blockCount: Int,
    entries: ZStream[Any, StoreError, ManifestEntry],
    ingestedAt: Instant,
  ): IO[StoreError, Unit] =
    val path = pathFor(blob)
    ZIO
      .fromEither(BlobManifestRepo.validateStreamArguments(blob, totalSize, blockCount))
      .mapError(StoreError.InvalidInput(StoreOperation.PutManifest, _)) *>
      ZIO
        .scoped {
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
        .mapError(StoreError.fromThrowable(StoreOperation.PutManifest, StoreBackend.Filesystem))

  override def get(blob: BinaryKey.Blob): IO[StoreError, Option[StoredManifest]] =
    val path = pathFor(blob)
    ZIO
      .attemptBlocking(Files.exists(path, LinkOption.NOFOLLOW_LINKS))
      .flatMap {
        case false => ZIO.none
        case true  => readStreamingManifest(path).map(Some(_))
      }
      .mapError(StoreError.fromThrowable(StoreOperation.GetManifest, StoreBackend.Filesystem))

  override def getSummary(blob: BinaryKey.Blob): IO[StoreError, Option[StoredManifestSummary]] =
    val path = pathFor(blob)
    ZIO
      .attemptBlocking(Files.exists(path, LinkOption.NOFOLLOW_LINKS))
      .flatMap {
        case false => ZIO.none
        case true  => readSummary(path).map(Some(_))
      }
      .mapError(StoreError.fromThrowable(StoreOperation.GetManifest, StoreBackend.Filesystem))

  override def inventoryPage(
    after: Option[InventoryCursor],
    limit: InventoryPageSize,
  ): IO[StoreError, InventoryPage[(BinaryKey.Blob, StoredManifestSummary)]] =
    for
      anchor <- ZIO
                  .fromEither(
                    after.fold[Either[String, Option[String]]](Right(None))(cursor =>
                      InventoryCursor.decode(cursor, InventoryNamespace.Filesystem).map(Some(_))
                    )
                  )
                  .mapError(StoreError.InvalidInput(StoreOperation.Inventory, _))
      paths  <- smallestPathsAfter(anchor, limit.value + 1)
                  .mapError(StoreError.fromThrowable(StoreOperation.Inventory, StoreBackend.Filesystem))
      items  <- ZIO.foreach(paths.take(limit.value)) { path =>
                  for
                    blob    <- ZIO.fromEither(keyFromPath(path)).mapError(StoreError.CorruptData(StoreOperation.Inventory, _))
                    summary <- readSummary(path).mapError(StoreError.fromThrowable(StoreOperation.Inventory, StoreBackend.Filesystem))
                  yield blob -> summary
                }
      next   <- ZIO.foreach(paths.lift(limit.value - 1).filter(_ => paths.length > limit.value)) { path =>
                  ZIO
                    .fromEither(InventoryCursor.encode(InventoryNamespace.Filesystem, relativePath(path)))
                    .mapError(StoreError.InvalidInput(StoreOperation.Inventory, _))
                }
    yield InventoryPage(Chunk.fromIterable(items), next)

  override def streamBlockRefs(blob: BinaryKey.Blob): ZStream[Any, StoreError, BlobStreamer.BlockRef] =
    val path = pathFor(blob)
    ZStream
      .unwrap {
        ZIO.attemptBlocking(Files.exists(path, LinkOption.NOFOLLOW_LINKS)).flatMap {
          case false => ZIO.fail(StoreError.NotFound(StoreOperation.GetManifest, blob))
          case true  =>
            ZIO.succeed {
              StreamingManifestFile.streamEntries(path).zipWithIndex.map { case (entry, index) =>
                entry.key match
                  case block: BinaryKey.Block => BlobStreamer.BlockRef(index, block)
                  case other                  =>
                    throw new IllegalArgumentException(
                      s"CAS manifest entry $index must reference a block key, got $other"
                    )
              }
            }
        }
      }
      .mapError(StoreError.fromThrowable(StoreOperation.GetManifest, StoreBackend.Filesystem))

  override def delete(blob: BinaryKey.Blob): IO[StoreError, Boolean] =
    ZIO
      .attemptBlocking(Files.deleteIfExists(pathFor(blob)))
      .mapError(StoreError.fromThrowable(StoreOperation.DeleteManifest, StoreBackend.Filesystem))

  override def healthCheck: IO[StoreError, Unit] =
    ZIO
      .attemptBlocking {
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
      .mapError(StoreError.fromThrowable(StoreOperation.HealthCheck, StoreBackend.Filesystem))

  private[stores] def pathFor(blob: BinaryKey.Blob): Path =
    val algo = blob.bits.algo.primaryName.toLowerCase.replace("-", "")
    val name = s"${blob.bits.digest.hex.value}-${blob.bits.size}.manifest"
    root.resolve(prefix).resolve(algo).resolve(name)

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
    for
      header     <- StreamingManifestFile.readHeader(path)
      ingestedAt <- ZIO.attemptBlocking(Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant)
    yield StoredManifestSummary(header.totalSize, header.blockCount, ingestedAt)

  private def smallestPathsAfter(anchor: Option[String], count: Int): Task[Vector[Path]] =
    ZIO.attemptBlocking {
      val manifestsRoot = root.resolve(prefix)
      if !Files.exists(manifestsRoot, LinkOption.NOFOLLOW_LINKS) then Vector.empty
      else if !Files.isDirectory(manifestsRoot, LinkOption.NOFOLLOW_LINKS) then
        throw new IllegalStateException(s"Manifest root is not a directory: $manifestsRoot")
      else
        val paths = Files.walk(manifestsRoot)
        try
          val selected = new java.util.PriorityQueue[Path](count, (left, right) => relativePath(right).compareTo(relativePath(left)))
          paths.iterator().asScala.foreach { path =>
            val relative = relativePath(path)
            if path.getFileName.toString.endsWith(".manifest") &&
              Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
              anchor.forall(_ < relative)
            then
              if selected.size() < count then
                val _ = selected.add(path)
              else if relative < relativePath(selected.peek()) then
                val _ = selected.poll()
                val _ = selected.add(path)
          }
          selected.iterator().asScala.toVector.sortBy(relativePath)
        finally paths.close()
    }

  private def relativePath(path: Path): String =
    root.resolve(prefix).relativize(path).iterator().asScala.map(_.toString).mkString("/")

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
  /** Historical public safety bound; the active GVM2 reader is streaming. */
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
