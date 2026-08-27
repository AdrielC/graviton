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
    onQuarantined: QuarantinedBlock => Task[Unit]
  ): Task[GarbageCollector.SweepReport]

  def restore(quarantined: ZStream[Any, Throwable, QuarantinedBlock]): Task[Long]

  def purge(
    quarantined: ZStream[Any, Throwable, QuarantinedBlock],
    minimumQuarantineAge: Duration,
  ): Task[Long]

object GarbageCollection:

  val service: ZIO[GarbageCollection, Nothing, GarbageCollection] =
    ZIO.service[GarbageCollection]

  def sweep(
    minimumAge: Duration,
    dryRun: Boolean,
  )(
    onQuarantined: QuarantinedBlock => Task[Unit]
  ): ZIO[GarbageCollection, Throwable, GarbageCollector.SweepReport] =
    ZIO.serviceWithZIO[GarbageCollection](_.sweep(minimumAge, dryRun)(onQuarantined))

  def sweep(
    minimumAge: Duration,
    dryRun: Boolean,
  ): ZIO[GarbageCollection, Throwable, GarbageCollector.SweepReport] =
    sweep(minimumAge, dryRun)(_ => ZIO.unit)

  def restore(
    quarantined: ZStream[Any, Throwable, QuarantinedBlock]
  ): ZIO[GarbageCollection, Throwable, Long] =
    ZIO.serviceWithZIO[GarbageCollection](_.restore(quarantined))

  def purge(
    quarantined: ZStream[Any, Throwable, QuarantinedBlock],
    minimumQuarantineAge: Duration,
  ): ZIO[GarbageCollection, Throwable, Long] =
    ZIO.serviceWithZIO[GarbageCollection](_.purge(quarantined, minimumQuarantineAge))

  /**
   * Production layer. The configuration dependency is a value service so
   * applications can choose the ZIO Config layer, a deployment-specific
   * layer, or a deterministic test value independently of storage adapters.
   */
  val live: ZLayer[
    BlobManifestRepo & BlockMaintenance & GarbageCollectionConfig,
    IllegalArgumentException,
    GarbageCollection,
  ] =
    ZLayer.fromZIO(ZIO.service[GarbageCollectionConfig].flatMap(make))

  /** Deterministic default wiring for embedded tools and simple local use. */
  val default: URLayer[BlobManifestRepo & BlockMaintenance, GarbageCollection] =
    ZLayer.fromFunction((manifests: BlobManifestRepo, blocks: BlockMaintenance) =>
      new GarbageCollector(manifests, blocks, GarbageCollectionConfig.Default): GarbageCollection
    )

  /**
   * Build the service with one explicit configuration value. This is useful in
   * focused tests and keeps configuration construction separate from storage
   * implementation wiring.
   */
  def configured(
    config: GarbageCollectionConfig
  ): ZLayer[BlobManifestRepo & BlockMaintenance, IllegalArgumentException, GarbageCollection] =
    ZLayer.fromZIO(make(config))

  private def make(
    config: GarbageCollectionConfig
  ): ZIO[BlobManifestRepo & BlockMaintenance, IllegalArgumentException, GarbageCollection] =
    for
      manifests <- ZIO.service[BlobManifestRepo]
      blocks    <- ZIO.service[BlockMaintenance]
      valid     <- ZIO.fromEither(config.validate).mapError(new IllegalArgumentException(_))
    yield new GarbageCollector(manifests, blocks, valid): GarbageCollection
