package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.core.types.{BlobOffset, FileSize}
import graviton.runtime.model.{BlobDescription, BlobListing, BlobStat, BlobWritePlan}
import zio.*
import zio.stream.*

/**
 * Holds a shared repository permit for the complete lifetime of each logical
 * blob operation, including sink input consumption and stream output demand.
 */
final class CoordinatedBlobStore(
  underlying: BlobStore,
  coordinator: MaintenanceCoordinator,
) extends BlobStore:

  override def put(plan: BlobWritePlan = BlobWritePlan()): BlobSink =
    ZSink.unwrapScoped(coordinator.operationPermit.as(underlying.put(plan)))

  override def get(key: BinaryKey.Blob): ZStream[Any, Throwable, Byte] =
    ZStream.unwrapScoped(coordinator.operationPermit.as(underlying.get(key)))

  override def getRange(
    key: BinaryKey.Blob,
    start: BlobOffset,
    length: FileSize,
  ): ZStream[Any, Throwable, Byte] =
    ZStream.unwrapScoped(coordinator.operationPermit.as(underlying.getRange(key, start, length)))

  override def stat(key: BinaryKey.Blob): Task[Option[BlobStat]] =
    coordinator.withOperation(underlying.stat(key))

  override def list: Task[Chunk[BlobListing]] =
    coordinator.withOperation(underlying.list)

  override def inspect(key: BinaryKey.Blob): Task[Option[BlobDescription]] =
    coordinator.withOperation(underlying.inspect(key))

  override def delete(key: BinaryKey.Blob): Task[Unit] =
    coordinator.withOperation(underlying.delete(key))

  override def healthCheck: Task[Unit] =
    underlying.healthCheck *> coordinator.healthCheck

object CoordinatedBlobStore:
  val layer: ZLayer[BlobStore & MaintenanceCoordinator, Nothing, BlobStore] =
    ZLayer.fromFunction((store: BlobStore, coordinator: MaintenanceCoordinator) => new CoordinatedBlobStore(store, coordinator): BlobStore)
