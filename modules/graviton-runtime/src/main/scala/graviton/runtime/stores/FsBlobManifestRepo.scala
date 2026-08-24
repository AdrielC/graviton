package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.core.manifest.{FramedManifest, Manifest}
import graviton.runtime.streaming.BlobStreamer
import zio.*
import zio.stream.ZStream

import java.nio.file.attribute.FileTime
import java.nio.file.{AtomicMoveNotSupportedException, Files, LinkOption, Path, StandardCopyOption}
import java.time.Instant

/**
 * Durable, filesystem-backed manifest repository.
 *
 * Manifests use `FramedManifest`'s versioned binary format and are written via
 * a temporary file plus atomic rename. The file's modification time records the
 * ingestion timestamp returned by [[BlobStore.stat]].
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

  override def get(blob: BinaryKey.Blob): ZIO[Any, Throwable, Option[StoredManifest]] =
    val path = pathFor(blob)
    ZIO.attemptBlocking {
      if !Files.exists(path, LinkOption.NOFOLLOW_LINKS) then None
      else if !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then
        throw new IllegalStateException(s"Manifest path is not a regular file: $path")
      else
        val size = Files.size(path)
        if size > FsBlobManifestRepo.MaxManifestBytes then
          throw new IllegalArgumentException(
            s"Manifest exceeds ${FsBlobManifestRepo.MaxManifestBytes} byte safety limit: $path"
          )

        val frame      = FramedManifest.Frame(Files.readAllBytes(path))
        val manifest   = FramedManifest
          .decode(frame)
          .fold(message => throw new IllegalArgumentException(s"Invalid manifest at $path: $message"), identity)
        val ingestedAt = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant
        Some(StoredManifest(manifest, ingestedAt))
    }

  override def streamBlockRefs(blob: BinaryKey.Blob): ZStream[Any, Throwable, BlobStreamer.BlockRef] =
    ZStream.unwrap {
      get(blob).flatMap {
        case None         =>
          ZIO.fail(new NoSuchElementException(s"Missing manifest for ${blob.bits.render}"))
        case Some(stored) =>
          ZIO
            .foreach(stored.manifest.entries.zipWithIndex) { case (entry, index) =>
              entry.key match
                case block: BinaryKey.Block => ZIO.succeed(BlobStreamer.BlockRef(index.toLong, block))
                case other                  =>
                  ZIO.fail(
                    new IllegalArgumentException(
                      s"CAS manifest entry $index must reference a block key, got $other"
                    )
                  )
            }
            .map(ZStream.fromIterable)
      }
    }

  override def delete(blob: BinaryKey.Blob): ZIO[Any, Throwable, Boolean] =
    ZIO.attemptBlocking(Files.deleteIfExists(pathFor(blob)))

  private[stores] def pathFor(blob: BinaryKey.Blob): Path =
    val algo = blob.bits.algo.primaryName.toLowerCase.replace("-", "")
    val name = s"${blob.bits.digest.hex.value}-${blob.bits.size}.manifest"
    root.resolve(prefix).resolve(algo).resolve(name)

  private def writeAtomically(path: Path, bytes: Array[Byte], ingestedAt: Instant): Task[Unit] =
    ZIO.attemptBlocking {
      Files.createDirectories(path.getParent)
      val tmp = Files.createTempFile(path.getParent, ".manifest-", ".tmp")
      try
        Files.write(tmp, bytes)
        try Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        catch
          case _: AtomicMoveNotSupportedException =>
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        Files.setLastModifiedTime(path, FileTime.from(ingestedAt))
        ()
      finally
        try
          val _ = Files.deleteIfExists(tmp)
          ()
        catch case _: java.io.IOException => ()
    }

object FsBlobManifestRepo:
  val MaxManifestBytes: Long = 64L * 1024L * 1024L

  def layer(root: Path, prefix: String = "cas/manifests"): ULayer[BlobManifestRepo] =
    ZLayer.succeed(new FsBlobManifestRepo(root, prefix))
