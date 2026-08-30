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
 * Ephemeral manifest repository for tests and embedded in-memory runtimes.
 *
 * Payload bytes never enter this repository. The map contains only bounded
 * manifests and their timestamps; arbitrary-size reads still stream block
 * references into the configured [[BlockStore]].
 */
final class InMemoryBlobManifestRepo private (
  state: Ref[Map[BinaryKey.Blob, StoredManifest]]
) extends BlobManifestRepo:

  override def put(blob: BinaryKey.Blob, manifest: Manifest, ingestedAt: Instant): IO[StoreError, Unit] =
    state.update(_.updated(blob, StoredManifest(manifest, ingestedAt))).unit

  override def get(blob: BinaryKey.Blob): IO[StoreError, Option[StoredManifest]] =
    state.get.map(_.get(blob))

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
      values <- state.get
      ordered = values.toList.sortBy(_._1.bits.render)
      page    = ordered.dropWhile { case (key, _) => anchor.exists(_ >= key.bits.render) }.take(limit.value + 1)
      items   = Chunk.fromIterable(page.take(limit.value).map { case (key, stored) =>
                  val summary = StoredManifestSummary(
                    FileSize.unsafe(stored.manifest.size),
                    stored.manifest.entries.length,
                    stored.ingestedAt,
                  )
                  key -> summary
                })
      next   <- ZIO.foreach(page.lift(limit.value - 1).filter(_ => page.length > limit.value)) { case (key, _) =>
                  ZIO
                    .fromEither(InventoryCursor.encode(InventoryNamespace.InMemory, key.bits.render))
                    .mapError(StoreError.InvalidInput(StoreOperation.Inventory, _))
                }
    yield InventoryPage(items, next)

  override def streamBlockRefs(blob: BinaryKey.Blob): ZStream[Any, StoreError, BlobStreamer.BlockRef] =
    ZStream.fromZIO(state.get.map(_.get(blob))).flatMap {
      case None         => ZStream.fail(StoreError.NotFound(StoreOperation.GetManifest, blob))
      case Some(stored) =>
        ZStream.fromIterable(
          stored.manifest.entries.zipWithIndex.collect { case (ManifestEntry(block: BinaryKey.Block, _, _), index) =>
            BlobStreamer.BlockRef(index.toLong, block)
          }
        )
    }

  override def delete(blob: BinaryKey.Blob): IO[StoreError, Boolean] =
    state.modify(manifests => (manifests.contains(blob), manifests - blob))

  override def healthCheck: IO[StoreError, Unit] = ZIO.unit

object InMemoryBlobManifestRepo:
  def make: UIO[InMemoryBlobManifestRepo] =
    Ref.make(Map.empty[BinaryKey.Blob, StoredManifest]).map(new InMemoryBlobManifestRepo(_))
