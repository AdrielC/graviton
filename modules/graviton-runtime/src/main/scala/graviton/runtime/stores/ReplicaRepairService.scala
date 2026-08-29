package graviton.runtime.stores

import graviton.runtime.config.ReplicationConfig
import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import zio.*

/** Bounded, fair background scrub of every block referenced by a manifest. */
final class ReplicaRepairService private (
  store: ReplicatedBlockStore,
  manifests: BlobManifestRepo,
  config: ReplicationConfig,
  cursor: Ref[Int],
  metrics: MetricsRegistry,
):
  import ReplicaRepairService.*

  def runCycle: Task[CycleReport] =
    ZIO.logAnnotate("component", "replica-repair") {
      for
        started <- Clock.nanoTime
        offset  <- cursor.get
        report  <- referencedBlocks
                     .drop(offset)
                     .take(config.repairBatchSize.value.toLong)
                     .mapZIO(key => store.repair(key).either)
                     .runFold(CycleAcc.empty) {
                       case (acc, Right(current)) => acc.record(current)
                       case (acc, Left(_))        => acc.failedBlock
                     }
        next     =
          if report.processed < config.repairBatchSize.value.toLong || offset > Int.MaxValue - report.processed then 0
          else offset + report.processed.toInt
        _       <- cursor.set(next)
        ended   <- Clock.nanoTime
        duration = (ended - started).max(0L).toDouble / 1_000_000_000.0
        result   = CycleReport(
                     processedBlocks = report.processed,
                     repairedReplicas = report.repaired,
                     failedReplicas = report.failedReplicas,
                     failedBlocks = report.failedBlocks,
                     nextOffset = next.toLong,
                     duration = duration,
                   )
        _       <- metrics.counter(
                     MetricKeys.ReplicaRepairCyclesTotal,
                     Map("outcome" -> (if result.failedBlocks == 0L then "succeeded" else "degraded")),
                   )
        _       <- metrics.histogram(MetricKeys.ReplicaRepairCycleDuration, duration, Map.empty)
        _       <- metrics.gauge(MetricKeys.ReplicaRepairFailedBlocks, result.failedBlocks.toDouble, Map.empty)
        _       <- ZIO.logInfo(
                     s"Replica repair cycle processed=${result.processedBlocks} repaired=${result.repairedReplicas} " +
                       s"failed_blocks=${result.failedBlocks} next_offset=${result.nextOffset}"
                   )
      yield result
    }

  /** Start one supervised scoped worker. No detached repair fibers are used. */
  def start: URIO[Scope, Unit] =
    runCycle
      .catchAllCause(cause => ZIO.logErrorCause("Replica repair cycle failed", cause))
      .repeat(Schedule.spaced(config.repairInterval))
      .forkScoped
      .unit

  private def referencedBlocks =
    manifests.streamSummaries.flatMap { case (blob, _) =>
      manifests.streamBlockRefs(blob).map(_.key)
    }

object ReplicaRepairService:
  final case class CycleReport(
    processedBlocks: Long,
    repairedReplicas: Long,
    failedReplicas: Long,
    failedBlocks: Long,
    nextOffset: Long,
    duration: Double,
  )

  def make(
    store: ReplicatedBlockStore,
    manifests: BlobManifestRepo,
    config: ReplicationConfig,
    metrics: MetricsRegistry = MetricsRegistry.noop,
  ): UIO[ReplicaRepairService] =
    Ref.make(0).map(new ReplicaRepairService(store, manifests, config, _, metrics))

  private final case class CycleAcc(
    processed: Long,
    repaired: Long,
    failedReplicas: Long,
    failedBlocks: Long,
  ):
    def record(report: ReplicatedBlockStore.RepairReport): CycleAcc =
      copy(
        processed = processed + 1L,
        repaired = repaired + report.repairedReplicas.toLong,
        failedReplicas = failedReplicas + report.failedReplicas.size.toLong,
        failedBlocks = failedBlocks + (if report.failedReplicas.nonEmpty then 1L else 0L),
      )

    def failedBlock: CycleAcc = copy(processed = processed + 1L, failedBlocks = failedBlocks + 1L)

  private object CycleAcc:
    val empty: CycleAcc = CycleAcc(0L, 0L, 0L, 0L)
