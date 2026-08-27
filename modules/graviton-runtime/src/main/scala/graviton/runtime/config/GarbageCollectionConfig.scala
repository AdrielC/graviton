package graviton.runtime.config

import zio.*
import zio.Config

import java.nio.file.Path

/**
 * Resource limits and operator-controlled workspace placement for repository
 * garbage collection.
 *
 * The configuration is deliberately separate from the storage ports. It can
 * be supplied directly in tests or loaded through ZIO Config from
 * `GRAVITON_GC_*` environment variables. The reference-partition limit is a
 * heap cardinality bound: a sweep holds at most that many block keys in one
 * partition set, plus a 256-record I/O batch and a backend cursor/page.
 */
final case class GarbageCollectionConfig(
  maxReferencesPerPartition: Int = GarbageCollectionConfig.DefaultMaxReferencesPerPartition,
  maximumPartitionDepth: Int = GarbageCollectionConfig.DefaultMaximumPartitionDepth,
  maxCompatibilityReferences: Int = GarbageCollectionConfig.DefaultMaxCompatibilityReferences,
  maxCompatibilityReceipts: Int = GarbageCollectionConfig.DefaultMaxCompatibilityReceipts,
  workspaceDirectory: Option[Path] = None,
):

  def validate: Either[String, GarbageCollectionConfig] =
    for
      _ <- Either.cond(
             maxReferencesPerPartition >= 1,
             (),
             "maxReferencesPerPartition must be at least 1",
           )
      _ <- Either.cond(
             maximumPartitionDepth >= 1 && maximumPartitionDepth <= 128,
             (),
             "maximumPartitionDepth must be within 1..128",
           )
      _ <- Either.cond(
             maxCompatibilityReferences >= 1,
             (),
             "maxCompatibilityReferences must be at least 1",
           )
      _ <- Either.cond(
             maxCompatibilityReceipts >= 1,
             (),
             "maxCompatibilityReceipts must be at least 1",
           )
    yield this

object GarbageCollectionConfig:

  val DefaultMaxReferencesPerPartition: Int  = 8192
  val DefaultMaximumPartitionDepth: Int      = 64
  val DefaultMaxCompatibilityReferences: Int = 100000
  val DefaultMaxCompatibilityReceipts: Int   = 10000

  val Default: GarbageCollectionConfig = GarbageCollectionConfig()

  /**
   * ZIO Config descriptor rooted at `graviton.gc`, which maps to the
   * `GRAVITON_GC_*` environment-variable family with the default provider.
   */
  val config: Config[GarbageCollectionConfig] =
    (Config.int("max-references-per-partition").withDefault(DefaultMaxReferencesPerPartition) ++
      Config.int("maximum-partition-depth").withDefault(DefaultMaximumPartitionDepth) ++
      Config.int("max-compatibility-references").withDefault(DefaultMaxCompatibilityReferences) ++
      Config.int("max-compatibility-receipts").withDefault(DefaultMaxCompatibilityReceipts) ++
      Config.string("workspace-directory").optional.mapAttempt(_.map(value => new java.io.File(value).toPath)))
      .map { case (maxRefs, maxDepth, compatibilityRefs, compatibilityReceipts, workspace) =>
        GarbageCollectionConfig(maxRefs, maxDepth, compatibilityRefs, compatibilityReceipts, workspace)
      }
      .mapOrFail(_.validate.left.map(message => Config.Error.InvalidData(Chunk.empty, message)))
      .nested("gc")
      .nested("graviton")

  /** Fail-fast configuration layer for applications that use the GC service. */
  val layer: ZLayer[Any, Config.Error, GarbageCollectionConfig] =
    ZLayer.fromZIO(ZIO.config(config))

  /** Explicit deterministic defaults for tests and embedded deployments. */
  val default: ULayer[GarbageCollectionConfig] =
    ZLayer.succeed(Default)
