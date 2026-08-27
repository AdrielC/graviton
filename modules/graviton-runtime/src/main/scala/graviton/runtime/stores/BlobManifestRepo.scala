package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.core.manifest.{FramedManifest, Manifest, ManifestEntry}
import graviton.core.types.FileSize
import graviton.runtime.streaming.BlobStreamer
import zio.ZIO
import zio.Chunk
import zio.stream.ZStream

import java.time.Instant

/**
 * A manifest paired with the timestamp at which it was persisted.
 *
 * The `ingestedAt` value is captured via `Clock.instant` during [[BlobManifestRepo.put]]
 * so that [[BlobStore.stat]] can return an honest `lastModified` timestamp.
 */
final case class StoredManifest(manifest: Manifest, ingestedAt: Instant)

/** Metadata that can be read without materializing every manifest entry. */
final case class StoredManifestSummary(
  totalSize: FileSize,
  blockCount: Int,
  ingestedAt: Instant,
)

/**
 * Persistence and streaming of blob structure (manifest).
 *
 * This is intentionally "bytes last": implementations should stream structural rows (block refs)
 * and let the block store stream payload bytes.
 */
trait BlobManifestRepo:
  def put(blob: BinaryKey.Blob, manifest: Manifest, ingestedAt: Instant): ZIO[Any, Throwable, Unit]

  /**
   * Persist ordered entries from a bounded-memory stream.
   *
   * Durable repositories override this to write entries incrementally. The
   * compatibility implementation is explicitly bounded by [[BlobManifestRepo.MaxEntries]]
   * and is intended for small in-memory or third-party repositories.
   */
  def putStream(
    blob: BinaryKey.Blob,
    totalSize: FileSize,
    blockCount: Int,
    entries: ZStream[Any, Throwable, ManifestEntry],
    ingestedAt: Instant,
  ): ZIO[Any, Throwable, Unit] =
    ZIO
      .fromEither(BlobManifestRepo.validateStreamArguments(blob, totalSize, blockCount))
      .mapError(new IllegalArgumentException(_)) *>
      entries
        .runFoldZIO((List.empty[ManifestEntry], 0)) { case ((reversed, count), entry) =>
          if count >= BlobManifestRepo.MaxEntries then
            ZIO.fail(
              new IllegalArgumentException(
                s"Manifest exceeds ${BlobManifestRepo.MaxEntries} entries"
              )
            )
          else ZIO.succeed((entry :: reversed, count + 1))
        }
        .flatMap { case (reversed, observedCount) =>
          if observedCount != blockCount then
            ZIO.fail(
              new IllegalArgumentException(
                s"Manifest entry count mismatch: expected $blockCount, observed $observedCount"
              )
            )
          else
            ZIO
              .fromEither(Manifest.fromEntries(reversed.reverse))
              .mapError(message => new IllegalArgumentException(message))
              .flatMap { manifest =>
                if manifest.size != totalSize.value then
                  ZIO.fail(
                    new IllegalArgumentException(
                      s"Manifest size mismatch: expected ${totalSize.value}, observed ${manifest.size}"
                    )
                  )
                else put(blob, manifest, ingestedAt)
              }
        }

  /** Retrieve the manifest and its ingestion timestamp for a blob, if it exists. */
  def get(blob: BinaryKey.Blob): ZIO[Any, Throwable, Option[StoredManifest]]

  /** Read size/count metadata without loading the entry list when supported. */
  def getSummary(blob: BinaryKey.Blob): ZIO[Any, Throwable, Option[StoredManifestSummary]] =
    get(blob).flatMap {
      case None         => ZIO.none
      case Some(stored) =>
        ZIO
          .fromEither(FileSize.either(stored.manifest.size))
          .mapError(message => new IllegalArgumentException(message))
          .map(size => Some(StoredManifestSummary(size, stored.manifest.entries.length, stored.ingestedAt)))
    }

  /** List every persisted blob manifest, newest first. */
  def list: ZIO[Any, Throwable, Chunk[(BinaryKey.Blob, StoredManifest)]]

  /** List metadata without loading every block reference when supported. */
  def listSummaries: ZIO[Any, Throwable, Chunk[(BinaryKey.Blob, StoredManifestSummary)]] =
    list.flatMap(entries =>
      ZIO.foreach(entries) { case (blob, stored) =>
        ZIO
          .fromEither(FileSize.either(stored.manifest.size))
          .mapError(message => new IllegalArgumentException(message))
          .map(size => blob -> StoredManifestSummary(size, stored.manifest.entries.length, stored.ingestedAt))
      }
    )

  /** Stream block refs in manifest order for read. */
  def streamBlockRefs(blob: BinaryKey.Blob): ZStream[Any, Throwable, BlobStreamer.BlockRef]

  /** Remove the manifest entry for a blob. Returns true if it existed. */
  def delete(blob: BinaryKey.Blob): ZIO[Any, Throwable, Boolean]

  /** Verify that manifest persistence is reachable. */
  def healthCheck: ZIO[Any, Throwable, Unit]

object BlobManifestRepo:
  /**
   * At 1 MiB minimum blocks this covers the public 1 TiB blob limit while
   * keeping all counters and on-disk formats within `Int` indexing.
   */
  val MaxEntries: Int             = 1024 * 1024
  val MaxMaterializedEntries: Int = FramedManifest.MaxManifestEntries

  def validateStreamArguments(
    blob: BinaryKey.Blob,
    totalSize: FileSize,
    blockCount: Int,
  ): Either[String, Unit] =
    for
      _ <- Either.cond(
             totalSize.value == blob.bits.size,
             (),
             s"Manifest size ${totalSize.value} does not match blob key size ${blob.bits.size}",
           )
      _ <- Either.cond(
             blockCount >= 1 && blockCount <= MaxEntries,
             (),
             s"Manifest block count $blockCount is outside 1..$MaxEntries",
           )
    yield ()

  val service: ZIO[BlobManifestRepo, Nothing, BlobManifestRepo] = ZIO.service[BlobManifestRepo]
