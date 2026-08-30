package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.core.manifest.{Manifest, ManifestEntry}
import graviton.core.types.{BlobOffset, FileSize}
import graviton.runtime.model.{InventoryCursor, InventoryPage, InventoryPageSize}
import graviton.runtime.streaming.BlobStreamer
import zio.*
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
  def put(blob: BinaryKey.Blob, manifest: Manifest, ingestedAt: Instant): IO[StoreError, Unit]

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
    entries: ZStream[Any, StoreError, ManifestEntry],
    ingestedAt: Instant,
  ): IO[StoreError, Unit] =
    ZIO
      .fromEither(BlobManifestRepo.validateStreamArguments(blob, totalSize, blockCount))
      .mapError(StoreError.InvalidInput(StoreOperation.PutManifest, _)) *>
      entries
        .runFoldZIO((List.empty[ManifestEntry], 0)) { case ((reversed, count), entry) =>
          if count >= BlobManifestRepo.MaxEntries then
            ZIO.fail(
              StoreError.InvalidInput(StoreOperation.PutManifest, s"Manifest exceeds ${BlobManifestRepo.MaxEntries} entries")
            )
          else ZIO.succeed((entry :: reversed, count + 1))
        }
        .flatMap { case (reversed, observedCount) =>
          if observedCount != blockCount then
            ZIO.fail(
              StoreError.InvalidInput(
                StoreOperation.PutManifest,
                s"Manifest entry count mismatch: expected $blockCount, observed $observedCount",
              )
            )
          else
            ZIO
              .fromEither(Manifest.fromEntries(reversed.reverse))
              .mapError(StoreError.InvalidInput(StoreOperation.PutManifest, _))
              .flatMap { manifest =>
                if manifest.size != totalSize.value then
                  ZIO.fail(
                    StoreError.InvalidInput(
                      StoreOperation.PutManifest,
                      s"Manifest size mismatch: expected ${totalSize.value}, observed ${manifest.size}",
                    )
                  )
                else put(blob, manifest, ingestedAt)
              }
        }

  /** Retrieve the manifest and its ingestion timestamp for a blob, if it exists. */
  def get(blob: BinaryKey.Blob): IO[StoreError, Option[StoredManifest]]

  /** Read size/count metadata without loading the entry list when supported. */
  def getSummary(blob: BinaryKey.Blob): IO[StoreError, Option[StoredManifestSummary]] =
    get(blob).flatMap {
      case None         => ZIO.none
      case Some(stored) =>
        ZIO
          .fromEither(FileSize.either(stored.manifest.size))
          .mapError(StoreError.CorruptData(StoreOperation.GetManifest, _))
          .map(size => Some(StoredManifestSummary(size, stored.manifest.entries.length, stored.ingestedAt)))
    }

  /** Return one bounded page in the repository's stable native order. */
  def inventoryPage(
    after: Option[InventoryCursor],
    limit: InventoryPageSize,
  ): IO[StoreError, InventoryPage[(BinaryKey.Blob, StoredManifestSummary)]]

  /**
   * Stream persisted blob summaries without loading their block references.
   *
   * Every page is obtained through the backend's native cursor contract. Any
   * page resource must remain scoped until that page completes; the stream
   * never materializes repository-scale state.
   */
  final def streamSummaries: ZStream[Any, StoreError, (BinaryKey.Blob, StoredManifestSummary)] =
    ZStream
      .paginateChunkZIO(Option.empty[InventoryCursor]) { cursor =>
        inventoryPage(cursor, InventoryPageSize.Maximum).map { page =>
          page.items -> page.next.map(Some(_))
        }
      }

  /** Stream block refs in manifest order for read. */
  def streamBlockRefs(blob: BinaryKey.Blob): ZStream[Any, StoreError, BlobStreamer.BlockRef]

  /**
   * Stream only block refs intersecting the requested half-open blob range.
   *
   * The default scans lightweight manifest refs, never block bodies. Durable
   * repositories should override this with an indexed range query.
   */
  def streamBlockRefsRange(
    blob: BinaryKey.Blob,
    start: BlobOffset,
    length: FileSize,
  ): ZStream[Any, StoreError, BlobStreamer.RangedBlockRef] =
    val requestedStart = start.value
    val requestedEnd   = java.lang.Math.addExact(requestedStart, length.value)

    streamBlockRefs(blob)
      .mapAccumZIO(0L) { (blockStart, ref) =>
        ZIO
          .attempt {
            val blockEnd = java.lang.Math.addExact(blockStart, ref.key.bits.size)
            blockEnd -> (ref, blockStart, blockEnd)
          }
          .mapError(StoreError.fromThrowable(StoreOperation.GetRange))
      }
      .collect {
        case (ref, blockStart, blockEnd) if blockStart < requestedEnd && blockEnd > requestedStart =>
          BlobStreamer.RangedBlockRef(ref.idx, ref.key, BlobOffset.unsafe(blockStart))
      }

  /** Remove the manifest entry for a blob. Returns true if it existed. */
  def delete(blob: BinaryKey.Blob): IO[StoreError, Boolean]

  /** Verify that manifest persistence is reachable. */
  def healthCheck: IO[StoreError, Unit]

object BlobManifestRepo:
  /**
   * At 1 MiB minimum blocks this covers the public 1 TiB blob limit while
   * keeping all counters and on-disk formats within `Int` indexing.
   */
  val MaxEntries: Int             = 1024 * 1024
  val MaxMaterializedEntries: Int = 16384

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
