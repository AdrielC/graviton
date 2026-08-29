package graviton.server

import graviton.integration.shardcake.{ShardcakeHealth, ShardcakeNode}
import graviton.runtime.metrics.{MetricKeys, MetricsRegistry, MetricsSnapshot}
import graviton.runtime.stores.BlobStore
import graviton.runtime.upload.ResumableUploadService
import zio.*

/** One operational read model shared by readiness and the local console. */
trait RuntimeHealth:
  def refresh: UIO[RuntimeHealth.Snapshot]

object RuntimeHealth:
  final case class Config(checkTimeout: Duration)

  object Config:
    val Default: Config = Config(5.seconds)

    val config: zio.Config[Config] =
      zio.Config
        .duration("check-timeout")
        .withDefault(Default.checkTimeout)
        .mapOrFail(value =>
          if value > Duration.Zero then Right(Config(value))
          else Left(zio.Config.Error.InvalidData(Chunk.empty, "check-timeout must be positive"))
        )
        .nested("health")
        .nested("graviton")

  enum StorageStatus:
    case Ready, Unavailable

  final case class ProcessMetrics(
    blobIngests: Long,
    bytesIngested: Long,
    freshBlocks: Long,
    duplicateBlocks: Long,
    localRoutes: Long,
    remoteRoutes: Long,
    localityFailures: Long,
  ):
    val totalBlocks: Long  = saturatingAdd(freshBlocks, duplicateBlocks)
    val reuseRatio: Double = if totalBlocks == 0L then 0.0 else duplicateBlocks.toDouble / totalBlocks.toDouble

  final case class Snapshot(
    storage: StorageStatus,
    shardcake: Option[ShardcakeHealth.Snapshot],
    process: ProcessMetrics,
    checkedAtMillis: Long,
  ):
    val ready: Boolean = storage == StorageStatus.Ready && shardcake.forall(_.ready)

  val refresh: ZIO[RuntimeHealth, Nothing, Snapshot] =
    ZIO.serviceWithZIO[RuntimeHealth](_.refresh)

  val live: ZLayer[BlobStore & ResumableUploadService & MetricsRegistry & Option[ShardcakeNode] & Config, Nothing, RuntimeHealth] =
    ZLayer.fromZIO {
      for
        blobStore <- ZIO.service[BlobStore]
        resumable <- ZIO.service[ResumableUploadService]
        metrics   <- ZIO.service[MetricsRegistry]
        shardcake <- ZIO.service[Option[ShardcakeNode]]
        config    <- ZIO.service[Config]
      yield Live(blobStore, resumable, shardcake.map(_.health), metrics, config)
    }

  private final case class Live(
    blobStore: BlobStore,
    resumable: ResumableUploadService,
    shardcake: Option[ShardcakeHealth],
    metrics: MetricsRegistry,
    config: Config,
  ) extends RuntimeHealth:
    override val refresh: UIO[Snapshot] =
      for
        storageResult <- blobStore.healthCheck.zipPar(resumable.healthCheck).timeout(config.checkTimeout).either
        cluster       <- ZIO.foreach(shardcake)(_.refresh)
        metricValues  <- metrics.snapshot
        checkedAt     <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
      yield Snapshot(
        storage = storageResult match
          case Right(Some(_)) => StorageStatus.Ready
          case _              => StorageStatus.Unavailable,
        shardcake = cluster,
        process = fromMetrics(metricValues),
        checkedAtMillis = checkedAt,
      )

  private def fromMetrics(snapshot: MetricsSnapshot): ProcessMetrics =
    def total(name: String, requiredTags: Map[String, String] = Map.empty): Long =
      snapshot.counters.iterator
        .collect {
          case (key, value) if key.name == name && requiredTags.forall { case (tag, expected) => key.tags.get(tag).contains(expected) } =>
            value
        }
        .foldLeft(0L)(saturatingAdd)

    ProcessMetrics(
      blobIngests = total(MetricKeys.BlobIngestsTotal),
      bytesIngested = total(MetricKeys.BytesIngestedTotal),
      freshBlocks = total(MetricKeys.FreshBlocksTotal),
      duplicateBlocks = total(MetricKeys.DuplicateBlocksTotal),
      localRoutes = total(MetricKeys.UploadLocalityDecisionsTotal, Map("route" -> "local")),
      remoteRoutes = total(MetricKeys.UploadLocalityDecisionsTotal, Map("route" -> "remote")),
      localityFailures = total(MetricKeys.UploadLocalityFailuresTotal),
    )

  private def saturatingAdd(left: Long, right: Long): Long =
    if right > 0L && left > Long.MaxValue - right then Long.MaxValue
    else if right < 0L && left < Long.MinValue - right then Long.MinValue
    else left + right
