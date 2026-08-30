package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.core.manifest.{Manifest, ManifestEntry}
import graviton.core.types.FileSize
import graviton.runtime.model.{InventoryCursor, InventoryNamespace, InventoryPage, InventoryPageSize}
import graviton.runtime.streaming.BlobStreamer
import zio.*
import zio.stream.ZStream

import java.time.Instant

/**
 * TEST-ONLY in-memory manifest repository.
 *
 * Shared across test suites that need a `BlobManifestRepo` without a database.
 */
final class InMemoryBlobManifestRepo private (
  ref: Ref[Map[BinaryKey.Blob, StoredManifest]]
) extends BlobManifestRepo:

  override def put(blob: BinaryKey.Blob, manifest: Manifest, ingestedAt: Instant): IO[StoreError, Unit] =
    ref.update(_.updated(blob, StoredManifest(manifest, ingestedAt))).unit

  override def get(blob: BinaryKey.Blob): IO[StoreError, Option[StoredManifest]] =
    ref.get.map(_.get(blob))

  override def inventoryPage(
    after: Option[InventoryCursor],
    limit: InventoryPageSize,
  ): IO[StoreError, InventoryPage[(BinaryKey.Blob, StoredManifestSummary)]] =
    for
      anchor <- ZIO
                  .fromEither(
                    after.fold[Either[String, Option[String]]](Right(None))(cursor =>
                      InventoryCursor.decode(cursor, InventoryNamespace.InMemory).map(Some(_))
                    )
                  )
                  .mapError(StoreError.InvalidInput(StoreOperation.Inventory, _))
      values <- ref.get
      ordered = values.toList.sortBy(_._1.bits.render)
      rows    = ordered.dropWhile { case (key, _) => anchor.exists(_ >= key.bits.render) }.take(limit.value + 1)
      items   = Chunk.fromIterable(rows.take(limit.value).map { case (key, stored) =>
                  key -> StoredManifestSummary(
                    FileSize.unsafe(stored.manifest.size),
                    stored.manifest.entries.length,
                    stored.ingestedAt,
                  )
                })
      next   <- ZIO.foreach(rows.lift(limit.value - 1).filter(_ => rows.length > limit.value)) { case (key, _) =>
                  ZIO
                    .fromEither(InventoryCursor.encode(InventoryNamespace.InMemory, key.bits.render))
                    .mapError(StoreError.InvalidInput(StoreOperation.Inventory, _))
                }
    yield InventoryPage(items, next)

  override def streamBlockRefs(blob: BinaryKey.Blob): ZStream[Any, StoreError, BlobStreamer.BlockRef] =
    ZStream.fromZIO(ref.get.map(_.get(blob))).flatMap {
      case None         =>
        ZStream.fail(StoreError.NotFound(StoreOperation.GetManifest, blob))
      case Some(stored) =>
        ZStream.fromIterable(
          stored.manifest.entries.zipWithIndex.collect { case (ManifestEntry(b: BinaryKey.Block, _, _), idx) =>
            BlobStreamer.BlockRef(idx.toLong, b)
          }
        )
    }

  override def delete(blob: BinaryKey.Blob): IO[StoreError, Boolean] =
    ref.modify { map =>
      if map.contains(blob) then (true, map - blob)
      else (false, map)
    }

  override def healthCheck: IO[StoreError, Unit] = ZIO.unit

  /** List all stored blob keys. */
  def keys: ZIO[Any, Nothing, Set[BinaryKey.Blob]] =
    ref.get.map(_.keySet)

  /** Get the raw map snapshot for assertions. */
  def snapshot: ZIO[Any, Nothing, Map[BinaryKey.Blob, StoredManifest]] =
    ref.get

object InMemoryBlobManifestRepo:
  def make: UIO[InMemoryBlobManifestRepo] =
    Ref.make(Map.empty[BinaryKey.Blob, StoredManifest]).map(new InMemoryBlobManifestRepo(_))
