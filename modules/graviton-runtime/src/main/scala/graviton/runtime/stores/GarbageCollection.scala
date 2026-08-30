package graviton.runtime.stores

import graviton.runtime.config.GarbageCollectionConfig
import zio.*
import zio.stream.ZStream

/**
 * Orthogonal maintenance port for reversible content-addressed-storage GC.
 *
 * Application code depends on this small service rather than on filesystem,
 * Postgres, or S3 implementation classes. The production API is streaming:
 * quarantine receipts are emitted to the callback as they are created, and
 * restore/purge consume receipt streams without collecting them in heap.
 */
trait GarbageCollection:
  def sweep(
    minimumAge: Duration,
    dryRun: Boolean,
  )(
    onQuarantined: QuarantinedBlock => IO[StoreError, Unit]
  ): IO[StoreError, GarbageCollector.SweepReport]

  def restore(quarantined: ZStream[Any, StoreError, QuarantinedBlock]): IO[StoreError, Long]

  def purge(
    quarantined: ZStream[Any, StoreError, QuarantinedBlock],
    minimumQuarantineAge: Duration,
  ): IO[StoreError, Long]

object GarbageCollection:

  val service: ZIO[GarbageCollection, Nothing, GarbageCollection] =
    ZIO.service[GarbageCollection]

  def sweep(
    minimumAge: Duration,
    dryRun: Boolean,
  )(
    onQuarantined: QuarantinedBlock => IO[StoreError, Unit]
  ): ZIO[GarbageCollection, StoreError, GarbageCollector.SweepReport] =
    ZIO.serviceWithZIO[GarbageCollection](_.sweep(minimumAge, dryRun)(onQuarantined))

  def sweep(
    minimumAge: Duration,
    dryRun: Boolean,
  ): ZIO[GarbageCollection, StoreError, GarbageCollector.SweepReport] =
    sweep(minimumAge, dryRun)(_ => ZIO.unit)

  def restore(
    quarantined: ZStream[Any, StoreError, QuarantinedBlock]
  ): ZIO[GarbageCollection, StoreError, Long] =
    ZIO.serviceWithZIO[GarbageCollection](_.restore(quarantined))

  def purge(
    quarantined: ZStream[Any, StoreError, QuarantinedBlock],
    minimumQuarantineAge: Duration,
  ): ZIO[GarbageCollection, StoreError, Long] =
    ZIO.serviceWithZIO[GarbageCollection](_.purge(quarantined, minimumQuarantineAge))

  /**
   * Production layer. The explicit coordinator must match the blob-store
   * coordinator for the same manifest and block stores. Configuration remains
   * an independent value service so applications can use ZIO Config or a
   * deterministic test value without coupling it to storage adapters.
   */
  val live: ZLayer[
    BlobManifestRepo & BlockMaintenance & GarbageCollectionConfig & MaintenanceCoordinator,
    IllegalArgumentException,
    GarbageCollection,
  ] =
    ZLayer.fromZIO(ZIO.service[GarbageCollectionConfig].flatMap(make))

  /** Domain-wide production layer for block namespaces shared by tenants. */
  val storageDomainLive: ZLayer[
    ManifestReferenceSource & BlockMaintenance & GarbageCollectionConfig & MaintenanceCoordinator,
    IllegalArgumentException,
    GarbageCollection,
  ] =
    ZLayer.fromZIO {
      for
        references  <- ZIO.service[ManifestReferenceSource]
        blocks      <- ZIO.service[BlockMaintenance]
        config      <- ZIO.service[GarbageCollectionConfig]
        coordinator <- ZIO.service[MaintenanceCoordinator]
        valid       <- ZIO.fromEither(config.validate).mapError(new IllegalArgumentException(_))
      yield GarbageCollector.forReferenceSource(references, blocks, valid, coordinator): GarbageCollection
    }

  /** Deterministic fiber-safe wiring for embedded tools and simple local use. */
  val default: URLayer[BlobManifestRepo & BlockMaintenance, GarbageCollection] =
    ZLayer.fromZIO {
      for
        manifests   <- ZIO.service[BlobManifestRepo]
        blocks      <- ZIO.service[BlockMaintenance]
        coordinator <- MaintenanceCoordinator.inProcess().orDie
      yield new GarbageCollector(
        manifests,
        blocks,
        GarbageCollectionConfig.Default,
        coordinator,
      ): GarbageCollection
    }

  /**
   * Build the service with one explicit configuration value. This is useful in
   * focused tests and keeps configuration construction separate from storage
   * implementation wiring.
   */
  def configured(
    config: GarbageCollectionConfig
  ): ZLayer[BlobManifestRepo & BlockMaintenance, IllegalArgumentException, GarbageCollection] =
    ZLayer.fromZIO {
      for
        manifests   <- ZIO.service[BlobManifestRepo]
        blocks      <- ZIO.service[BlockMaintenance]
        coordinator <- MaintenanceCoordinator.inProcess()
        valid       <- ZIO.fromEither(config.validate).mapError(new IllegalArgumentException(_))
      yield new GarbageCollector(manifests, blocks, valid, coordinator): GarbageCollection
    }

  private def make(
    config: GarbageCollectionConfig
  ): ZIO[BlobManifestRepo & BlockMaintenance & MaintenanceCoordinator, IllegalArgumentException, GarbageCollection] =
    for
      manifests   <- ZIO.service[BlobManifestRepo]
      blocks      <- ZIO.service[BlockMaintenance]
      coordinator <- ZIO.service[MaintenanceCoordinator]
      valid       <- ZIO.fromEither(config.validate).mapError(new IllegalArgumentException(_))
    yield new GarbageCollector(manifests, blocks, valid, coordinator): GarbageCollection
