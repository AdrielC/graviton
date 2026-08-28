package graviton.integration.shardcake

import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import graviton.runtime.upload.{UploadHotState, UploadNode, UploadPlacement}
import zio.*

/** A typed cached read model plus an explicit, instrumented live cluster probe. */
trait ShardcakeHealth:
  def current: UIO[ShardcakeHealth.Snapshot]
  def refresh: UIO[ShardcakeHealth.Snapshot]
  def readiness: IO[ShardcakeNode.HealthError, Unit]

object ShardcakeHealth:
  enum Status(val code: String, val ready: Boolean):
    case Starting    extends Status("starting", false)
    case Healthy     extends Status("healthy", true)
    case Rebalancing extends Status("rebalancing", true)
    case Unassigned  extends Status("unassigned", false)
    case Unavailable extends Status("unavailable", false)

  final case class Snapshot(
    status: Status,
    node: UploadNode,
    configuredShards: Int,
    assignedShards: Int,
    localAssignedShards: Int,
    observedNodes: Int,
    trackedSessions: Int,
    checkedAtMillis: Option[Long],
    lastSuccessfulAtMillis: Option[Long],
    detail: String,
  ):
    def ready: Boolean = status.ready

  val current: ZIO[ShardcakeHealth, Nothing, Snapshot] =
    ZIO.serviceWithZIO[ShardcakeHealth](_.current)

  val refresh: ZIO[ShardcakeHealth, Nothing, Snapshot] =
    ZIO.serviceWithZIO[ShardcakeHealth](_.refresh)

  val readiness: ZIO[ShardcakeHealth, ShardcakeNode.HealthError, Unit] =
    ZIO.serviceWithZIO[ShardcakeHealth](_.readiness)

  val live: ZLayer[ShardcakeUploadConfig & UploadPlacement & UploadHotState & MetricsRegistry, Nothing, ShardcakeHealth] =
    ZLayer.fromZIO {
      for
        config    <- ZIO.service[ShardcakeUploadConfig]
        placement <- ZIO.service[UploadPlacement]
        hotState  <- ZIO.service[UploadHotState]
        metrics   <- ZIO.service[MetricsRegistry]
        health    <- make(config, placement, hotState, metrics)
      yield health
    }

  def make(
    config: ShardcakeUploadConfig,
    placement: UploadPlacement,
    hotState: UploadHotState,
    metrics: MetricsRegistry,
  ): UIO[ShardcakeHealth] =
    Ref
      .make(
        Snapshot(
          status = Status.Starting,
          node = config.node,
          configuredShards = config.numberOfShards,
          assignedShards = 0,
          localAssignedShards = 0,
          observedNodes = 0,
          trackedSessions = 0,
          checkedAtMillis = None,
          lastSuccessfulAtMillis = None,
          detail = "Waiting for the first placement check",
        )
      )
      .map(ref => Live(config, placement, hotState, metrics, ref))

  private final case class Live(
    config: ShardcakeUploadConfig,
    placement: UploadPlacement,
    hotState: UploadHotState,
    metrics: MetricsRegistry,
    state: Ref[Snapshot],
  ) extends ShardcakeHealth:

    override val current: UIO[Snapshot] = state.get

    override def refresh: UIO[Snapshot] =
      probe.map(_._1)

    override def readiness: IO[ShardcakeNode.HealthError, Unit] =
      probe.flatMap {
        case (_, None)              => ZIO.unit
        case (_, Some(healthError)) => ZIO.fail(healthError)
      }

    private def probe: UIO[(Snapshot, Option[ShardcakeNode.HealthError])] =
      ZIO.logAnnotate(
        Set(
          LogAnnotation("component", "shardcake"),
          LogAnnotation("operation", "health_check"),
          LogAnnotation("node_id", config.node.id.value),
        )
      ) {
        for
          started          <- Clock.nanoTime
          previous         <- state.get
          checkedAt        <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          checked          <- check(checkedAt).either
          tracked          <- hotState.size
          result            = checked match
                                case Right(snapshot) => snapshot.copy(trackedSessions = tracked)            -> None
                                case Left(error)     => failedSnapshot(previous, error, tracked, checkedAt) -> Some(error)
          (snapshot, error) = result
          _                <- state.set(snapshot)
          finished         <- Clock.nanoTime
          _                <- recordMetrics(snapshot, (finished - started).toDouble / 1e9)
          _                <- logTransition(previous.status, snapshot, error)
        yield result
      }

    private def check(checkedAt: Long): IO[ShardcakeNode.HealthError, Snapshot] =
      for
        local       <- placement.localNode
        assignments <- placement.assignments
                         .mapError(ShardcakeNode.HealthError.PlacementUnavailable.apply)
                         .timeoutFail(
                           ShardcakeNode.HealthError.PlacementUnavailable(
                             UploadPlacement.Error.BackendFailure(
                               "health_check_timeout",
                               new java.util.concurrent.TimeoutException("Shard assignment check timed out"),
                             )
                           )
                         )(config.sendTimeout)
        localCount   = assignments.count(assignment => assignment.controlHost == local.host && assignment.controlPort == local.controlPort)
        _           <- ZIO.fail(ShardcakeNode.HealthError.LocalNodeUnassigned(local)).when(localCount == 0)
        nodes        = assignments.iterator.map(assignment => assignment.controlHost.value -> assignment.controlPort.value).toSet.size
        status       = if assignments.lengthCompare(config.numberOfShards) >= 0 then Status.Healthy else Status.Rebalancing
        detail       = status match
                         case Status.Healthy     => "All configured shards have an owner"
                         case Status.Rebalancing => "Uploads are available while shard assignments converge"
                         case _                  => ""
      yield Snapshot(
        status = status,
        node = local,
        configuredShards = config.numberOfShards,
        assignedShards = assignments.length,
        localAssignedShards = localCount,
        observedNodes = nodes,
        trackedSessions = 0,
        checkedAtMillis = Some(checkedAt),
        lastSuccessfulAtMillis = Some(checkedAt),
        detail = detail,
      )

    private def failedSnapshot(
      previous: Snapshot,
      error: ShardcakeNode.HealthError,
      trackedSessions: Int,
      checkedAt: Long,
    ): Snapshot =
      val (status, detail) = error match
        case _: ShardcakeNode.HealthError.LocalNodeUnassigned  =>
          Status.Unassigned -> "This node does not currently own an upload shard"
        case _: ShardcakeNode.HealthError.PlacementUnavailable =>
          Status.Unavailable -> "Shard assignment state is unavailable"
      previous.copy(
        status = status,
        assignedShards = 0,
        localAssignedShards = 0,
        observedNodes = 0,
        trackedSessions = trackedSessions,
        checkedAtMillis = Some(checkedAt),
        detail = detail,
      )

    private def recordMetrics(snapshot: Snapshot, durationSeconds: Double): UIO[Unit] =
      val statusTag = Map("status" -> snapshot.status.code)
      metrics.counter(MetricKeys.ShardcakeHealthChecksTotal, statusTag) *>
        metrics.histogram(MetricKeys.ShardcakeHealthDuration, durationSeconds, Map.empty) *>
        metrics.gauge(MetricKeys.ShardcakeReady, if snapshot.ready then 1.0 else 0.0, Map.empty) *>
        metrics.gauge(MetricKeys.ShardcakeAssignedShards, snapshot.assignedShards.toDouble, Map.empty) *>
        metrics.gauge(MetricKeys.ShardcakeLocalAssignedShards, snapshot.localAssignedShards.toDouble, Map.empty) *>
        metrics.gauge(MetricKeys.ShardcakeObservedNodes, snapshot.observedNodes.toDouble, Map.empty) *>
        metrics.gauge(MetricKeys.ShardcakeTrackedSessions, snapshot.trackedSessions.toDouble, Map.empty)

    private def logTransition(
      previous: Status,
      snapshot: Snapshot,
      error: Option[ShardcakeNode.HealthError],
    ): UIO[Unit] =
      if previous == snapshot.status then ZIO.unit
      else
        val message =
          s"Shardcake health changed from ${previous.code} to ${snapshot.status.code}: ${snapshot.detail}"
        error match
          case Some(cause) => ZIO.logWarningCause(message, Cause.fail(cause))
          case None        => ZIO.logInfo(message)
