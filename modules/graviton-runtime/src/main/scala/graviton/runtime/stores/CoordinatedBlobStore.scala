package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.core.types.{BlobOffset, FileSize}
import graviton.runtime.model.{
  BlobBlockDescription,
  BlobDescription,
  BlobInspectionPage,
  BlobListing,
  BlobStat,
  BlobWritePlan,
  InventoryCursor,
  InventoryPage,
  InventoryPageSize,
}
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
    ZSink.unwrapScoped(
      coordinator.operationPermit
        .mapError(error => StoreError.BackendFailure(StoreOperation.PutBlob, StoreBackend.Runtime, error, retryable = true))
        .as(underlying.put(plan))
    )

  override def get(key: BinaryKey.Blob): ZStream[Any, StoreError, Byte] =
    ZStream.unwrapScoped(operationPermit(StoreOperation.GetBlob).as(underlying.get(key)))

  override def getRange(
    key: BinaryKey.Blob,
    start: BlobOffset,
    length: FileSize,
  ): ZStream[Any, StoreError, Byte] =
    ZStream.unwrapScoped(operationPermit(StoreOperation.GetRange).as(underlying.getRange(key, start, length)))

  override def stat(key: BinaryKey.Blob): IO[StoreError, Option[BlobStat]] =
    withOperation(StoreOperation.StatBlob)(underlying.stat(key))

  override def inventoryPage(after: Option[InventoryCursor], limit: InventoryPageSize): IO[StoreError, InventoryPage[BlobListing]] =
    withOperation(StoreOperation.Inventory)(underlying.inventoryPage(after, limit))

  override def inspect(key: BinaryKey.Blob): IO[StoreError, Option[BlobDescription]] =
    withOperation(StoreOperation.InspectBlob)(underlying.inspect(key))

  override def streamBlockDescriptions(key: BinaryKey.Blob): ZStream[Any, StoreError, BlobBlockDescription] =
    ZStream.unwrapScoped(operationPermit(StoreOperation.InspectBlob).as(underlying.streamBlockDescriptions(key)))

  override def inspectPage(
    key: BinaryKey.Blob,
    after: Option[InventoryCursor],
    limit: InventoryPageSize,
  ): IO[StoreError, Option[BlobInspectionPage]] =
    withOperation(StoreOperation.InspectBlob)(underlying.inspectPage(key, after, limit))

  override def metadata(key: BinaryKey.Blob): IO[StoreError, Option[BlobMetadataV1]] =
    withOperation(StoreOperation.StatBlob)(underlying.metadata(key))

  override def delete(key: BinaryKey.Blob): IO[StoreError, Unit] =
    withOperation(StoreOperation.DeleteBlob)(underlying.delete(key))

  override def healthCheck: IO[StoreError, Unit] =
    underlying.healthCheck *> coordinator.healthCheck.mapError(error =>
      StoreError.BackendFailure(StoreOperation.HealthCheck, StoreBackend.Runtime, error, retryable = true)
    )

  private def operationPermit(operation: StoreOperation): ZIO[Scope, StoreError, Unit] =
    coordinator.operationPermit.mapError(error => StoreError.BackendFailure(operation, StoreBackend.Runtime, error, retryable = true))

  private def withOperation[A](operation: StoreOperation)(effect: IO[StoreError, A]): IO[StoreError, A] =
    ZIO.scoped(operationPermit(operation) *> effect)

object CoordinatedBlobStore:
  val layer: ZLayer[BlobStore & MaintenanceCoordinator, Nothing, BlobStore] =
    ZLayer.fromFunction((store: BlobStore, coordinator: MaintenanceCoordinator) => new CoordinatedBlobStore(store, coordinator): BlobStore)
