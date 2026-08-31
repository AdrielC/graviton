package graviton.runtime.stores

import graviton.runtime.config.ReplicationConfig
import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import zio.*

/** Bounded, fair background scrub of every block referenced by a manifest. */
final class ReplicaRepairService private (
  store: ConvergentBlockStore,
  references: ManifestReferenceSource,
  config: ReplicationConfig,
  journal: RepairJournal,
  metrics: MetricsRegistry,
):
  import ReplicaRepairService.*

  def runCycle: IO[StoreError, CycleReport] =
    ZIO.logAnnotate("component", "replica-repair") {
      for
        started     <- Clock.nanoTime
        offset      <- journal.loadCursor
        report      <- references.referencedBlocks.zipWithIndex
                         .dropWhile { case (_, index) => index < offset }
                         .map(_._1)
                         .take(config.repairBatchSize.value.toLong)
                         .mapZIO { key =>
                           store.converge(key).either.flatMap {
                             case Right(current) => journal.resolve(key).as(Right(current))
                             case Left(error)    => Clock.instant.flatMap(now => journal.recordFailure(key, error, now)).as(Left(error))
                           }
                         }
                         .runFold(CycleAcc.empty) {
                           case (acc, Right(current)) => acc.record(current)
                           case (acc, Left(_))        => acc.failedBlock
                         }
        next         =
          if report.processed < config.repairBatchSize.value.toLong || offset > Long.MaxValue - report.processed then 0L
          else offset + report.processed
        _           <- journal.checkpoint(next)
        ended       <- Clock.nanoTime
        duration     = (ended - started).max(0L).toDouble / 1_000_000_000.0
        result       = CycleReport(
                         processedBlocks = report.processed,
                         repairedReplicas = report.repaired,
                         failedReplicas = report.failedReplicas,
                         failedBlocks = report.failedBlocks,
                         nextOffset = next,
                         duration = duration,
                       )
        _           <- metrics.counter(
                         MetricKeys.ReplicaRepairCyclesTotal,
                         Map("outcome" -> (if result.failedBlocks == 0L then "succeeded" else "degraded")),
                       )
        _           <- metrics.histogram(MetricKeys.ReplicaRepairCycleDuration, duration, Map.empty)
        _           <- metrics.gauge(MetricKeys.ReplicaRepairFailedBlocks, result.failedBlocks.toDouble, Map.empty)
        _           <- metrics.gauge(MetricKeys.ReplicaUnderProtectedBlocks, result.failedBlocks.toDouble, Map.empty)
        _           <- metrics.gauge(MetricKeys.ReplicaRepairCursor, result.nextOffset.toDouble, Map.empty)
        deadLetters <- journal.deadLetters.runCount
        _           <- metrics.gauge(MetricKeys.ReplicaRepairDeadLetters, deadLetters.toDouble, Map.empty)
        now         <- Clock.instant
        _           <- metrics
                         .gauge(MetricKeys.ReplicaRepairLastSuccess, now.getEpochSecond.toDouble, Map.empty)
                         .when(result.failedBlocks == 0L)
        _           <- ZIO.logInfo(
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

object ReplicaRepairService:
  final case class CycleReport(
    processedBlocks: Long,
    repairedReplicas: Long,
    failedReplicas: Long,
    failedBlocks: Long,
    nextOffset: Long,
    duration: Double,
  )

  /** Source-compatible entry point for callers that use the concrete replicated store. */
  def make(
    store: ReplicatedBlockStore,
    manifests: BlobManifestRepo,
    config: ReplicationConfig,
    metrics: MetricsRegistry = MetricsRegistry.noop,
  ): UIO[ReplicaRepairService] =
    make(store: ConvergentBlockStore, manifests, config, metrics)

  def make(
    store: ConvergentBlockStore,
    manifests: BlobManifestRepo,
    config: ReplicationConfig,
    metrics: MetricsRegistry,
  ): UIO[ReplicaRepairService] =
    make(store, ManifestReferenceSource.repository(manifests), config, metrics)

  def make(
    store: ConvergentBlockStore,
    references: ManifestReferenceSource,
    config: ReplicationConfig,
    metrics: MetricsRegistry,
  ): UIO[ReplicaRepairService] =
    RepairJournal.inMemory.map(new ReplicaRepairService(store, references, config, _, metrics))

  def make(
    store: ConvergentBlockStore,
    manifests: BlobManifestRepo,
    config: ReplicationConfig,
    journal: RepairJournal,
    metrics: MetricsRegistry,
  ): UIO[ReplicaRepairService] =
    make(store, ManifestReferenceSource.repository(manifests), config, journal, metrics)

  def make(
    store: ConvergentBlockStore,
    references: ManifestReferenceSource,
    config: ReplicationConfig,
    journal: RepairJournal,
    metrics: MetricsRegistry,
  ): UIO[ReplicaRepairService] =
    ZIO.succeed(new ReplicaRepairService(store, references, config, journal, metrics))

  private final case class CycleAcc(
    processed: Long,
    repaired: Long,
    failedReplicas: Long,
    failedBlocks: Long,
  ):
    def record(report: RepairConvergence): CycleAcc =
      copy(
        processed = processed + 1L,
        repaired = repaired + report.repairedCopies.toLong,
        failedReplicas = failedReplicas + report.failedCopies.size.toLong,
        failedBlocks = failedBlocks + (if report.failedCopies.nonEmpty then 1L else 0L),
      )

    def failedBlock: CycleAcc = copy(processed = processed + 1L, failedBlocks = failedBlocks + 1L)

  private object CycleAcc:
    val empty: CycleAcc = CycleAcc(0L, 0L, 0L, 0L)
