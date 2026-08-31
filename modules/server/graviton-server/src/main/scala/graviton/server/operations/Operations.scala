package graviton.server.operations

import graviton.integration.redis.RedisAdmissionConfig
import graviton.integration.shardcake.ShardcakeHealth
import graviton.runtime.admission.{AdmissionOccupancy, DistributedAdmission}
import graviton.runtime.config.TransferMemoryConfig
import graviton.runtime.metrics.{MetricKeys, MetricsRegistry, MetricsSnapshot}
import graviton.runtime.stores.{StoreBackend, TransferBudget, TransferScope}
import graviton.runtime.upload.UploadNodeId
import graviton.server.RuntimeHealth
import graviton.shared.ApiJsonCodec
import zio.*
import zio.blocks.schema.{Schema, SchemaError}
import zio.stream.ZStream

/**
 * Bounded, provider-neutral operator read model.
 *
 * Prometheus remains the time-series boundary. This service provides the
 * current operational truth needed by humans and automation without exposing
 * payloads, tenant identifiers, or the unbounded metric registry.
 */
trait Operations:
  def current: UIO[Operations.Snapshot]
  def refresh: UIO[Operations.Snapshot]
  def events: ZStream[Any, Nothing, Operations.Event]

object Operations:
  private given Schema[UploadNodeId] = Schema[String].transform(
    value => UploadNodeId.either(value).fold(message => throw SchemaError.validationFailed(message), identity),
    _.value,
  )

  enum Status:
    case Ready, Degraded, Unavailable

  object Status:
    given Schema[Status] = Schema.derived

  enum CheckStatus:
    case Ready, Degraded, Unavailable, Inactive

  object CheckStatus:
    given Schema[CheckStatus] = Schema.derived

  enum CheckId:
    case Storage, Placement, LocalAdmission, DistributedAdmission, Durability, PostgreSql

  object CheckId:
    given Schema[CheckId] = Schema.derived

  final case class Check(
    id: CheckId,
    label: String,
    status: CheckStatus,
    detail: String,
  )

  object Check:
    given Schema[Check] = Schema.derived

  enum PlacementStatus(val code: String):
    case SingleNode  extends PlacementStatus("single-node")
    case Starting    extends PlacementStatus("starting")
    case Healthy     extends PlacementStatus("healthy")
    case Rebalancing extends PlacementStatus("rebalancing")
    case Unassigned  extends PlacementStatus("unassigned")
    case Unavailable extends PlacementStatus("unavailable")

  object PlacementStatus:
    given Schema[PlacementStatus] = Schema.derived

  final case class Placement(
    enabled: Boolean,
    status: PlacementStatus,
    nodeId: Option[UploadNodeId],
    configuredShards: Int,
    assignedShards: Int,
    localAssignedShards: Int,
    observedNodes: Int,
    trackedSessions: Int,
  )

  object Placement:
    given Schema[Placement] = Schema.derived

  final case class Capacity(
    localLimitBytes: Long,
    localAvailableBytes: Long,
    localUsedPercent: Int,
    distributedEnabled: Boolean,
    serviceBufferedBytes: Option[Long],
    serviceLimitBytes: Option[Long],
    serviceUsedPercent: Option[Int],
    serviceTransfers: Option[Long],
    serviceTransferLimit: Option[Int],
    backendTransfers: Option[Long],
    backendTransferLimit: Option[Int],
    admittedTransfers: Long,
    rejectedTransfers: Long,
    lostLeases: Long,
  )

  object Capacity:
    given Schema[Capacity] = Schema.derived

  final case class Durability(
    repairConfigured: Boolean,
    repairObserved: Boolean,
    healthyTargets: Long,
    underProtectedBlocks: Long,
    failedBlocks: Long,
    repairedCopies: Long,
    repairCursor: Long,
    lastSuccessfulRepairEpochSeconds: Option[Long],
  )

  object Durability:
    given Schema[Durability] = Schema.derived

  enum DependencyKind(val label: String):
    case PostgreSql extends DependencyKind("PostgreSQL")
    case S3         extends DependencyKind("S3")

  object DependencyKind:
    given Schema[DependencyKind] = Schema.derived

  final case class Dependency(
    kind: DependencyKind,
    observed: Boolean,
    activeConnections: Option[Long],
    maximumConnections: Option[Long],
    awaitingConnections: Option[Long],
    operations: Long,
    failures: Long,
    retries: Long,
  )

  object Dependency:
    given Schema[Dependency] = Schema.derived

  final case class Traffic(
    blobIngests: Long,
    bytesIngested: Long,
    freshBlocks: Long,
    duplicateBlocks: Long,
    freshBytes: Long,
    duplicateBytes: Long,
    byteReusePercent: Int,
    httpRequests: Long,
    httpErrors: Long,
    backendOperations: Long,
    backendFailures: Long,
  )

  object Traffic:
    given Schema[Traffic] = Schema.derived

  final case class Snapshot(
    sequence: Long,
    observedAtEpochMillis: Long,
    status: Status,
    summary: String,
    checks: List[Check],
    placement: Placement,
    capacity: Capacity,
    durability: Durability,
    dependencies: List[Dependency],
    traffic: Traffic,
  )

  object Snapshot:
    given Schema[Snapshot]       = Schema.derived
    given ApiJsonCodec[Snapshot] = ApiJsonCodec.derived

  final case class Event(sequence: Long, snapshot: Snapshot)

  object Event:
    given Schema[Event]       = Schema.derived
    given ApiJsonCodec[Event] = ApiJsonCodec.derived

  final case class Config(
    refreshInterval: Duration,
    eventCapacity: Int,
    pressureWarningPercent: Int,
  )

  object Config:
    val Default: Config = Config(
      refreshInterval = 5.seconds,
      eventCapacity = 64,
      pressureWarningPercent = 85,
    )

    val config: zio.Config[Config] =
      (zio.Config.duration("refresh-interval").withDefault(Default.refreshInterval) ++
        zio.Config.int("event-capacity").withDefault(Default.eventCapacity) ++
        zio.Config.int("pressure-warning-percent").withDefault(Default.pressureWarningPercent))
        .mapOrFail { case (refresh, capacity, warning) =>
          if refresh <= Duration.Zero then Left(zio.Config.Error.InvalidData(Chunk.empty, "refresh-interval must be positive"))
          else if capacity < 1 || capacity > 4096 then
            Left(zio.Config.Error.InvalidData(Chunk.empty, "event-capacity must be within 1..4096"))
          else if warning < 1 || warning > 100 then
            Left(zio.Config.Error.InvalidData(Chunk.empty, "pressure-warning-percent must be within 1..100"))
          else Right(Config(refresh, capacity, warning))
        }
        .nested("operations")
        .nested("graviton")

  def make(
    runtimeHealth: RuntimeHealth,
    metrics: MetricsRegistry,
    transferBudget: TransferBudget,
    transferMemory: TransferMemoryConfig,
    distributedAdmission: DistributedAdmission,
    distributedConfig: RedisAdmissionConfig,
    replicationConfigured: Boolean,
    config: Config,
  ): ZIO[Scope, Nothing, Operations] =
    for
      observed <- observe(runtimeHealth, metrics, transferBudget, distributedAdmission, distributedConfig.enabled)
      initial   = snapshotOf(1L, observed, transferMemory, distributedConfig, replicationConfigured, config)
      state    <- Ref.Synchronized.make(initial)
      hub      <- Hub.sliding[Event](config.eventCapacity)
      live      = Live(
                    runtimeHealth,
                    metrics,
                    transferBudget,
                    transferMemory,
                    distributedAdmission,
                    distributedConfig,
                    replicationConfigured,
                    config,
                    state,
                    hub,
                  )
      _        <- ZIO
                    .logAnnotate("component", "operations") {
                      (ZIO.sleep(config.refreshInterval) *> live.refresh).catchAllCause { cause =>
                        if cause.isInterrupted then ZIO.refailCause(cause)
                        else ZIO.logErrorCause("Operator snapshot refresh failed", cause)
                      }.forever
                    }
                    .forkScoped
    yield live

  private[server] def fixed(snapshot: Snapshot): Operations = new Operations:
    override val current: UIO[Snapshot]               = ZIO.succeed(snapshot)
    override val refresh: UIO[Snapshot]               = ZIO.succeed(snapshot)
    override val events: ZStream[Any, Nothing, Event] = ZStream.succeed(Event(snapshot.sequence, snapshot))

  private final case class Observed(
    health: RuntimeHealth.Snapshot,
    metrics: MetricsSnapshot,
    localAvailableBytes: Long,
    distributed: Option[Either[DistributedAdmission.Error, AdmissionOccupancy]],
    observedAtEpochMillis: Long,
  )

  private final case class Live(
    runtimeHealth: RuntimeHealth,
    metrics: MetricsRegistry,
    transferBudget: TransferBudget,
    transferMemory: TransferMemoryConfig,
    distributedAdmission: DistributedAdmission,
    distributedConfig: RedisAdmissionConfig,
    replicationConfigured: Boolean,
    config: Config,
    state: Ref.Synchronized[Snapshot],
    hub: Hub[Event],
  ) extends Operations:
    override val current: UIO[Snapshot] = state.get

    override val refresh: UIO[Snapshot] =
      observe(runtimeHealth, metrics, transferBudget, distributedAdmission, distributedConfig.enabled).flatMap { observed =>
        state.modifyZIO { previous =>
          val sequence = if previous.sequence == Long.MaxValue then Long.MaxValue else previous.sequence + 1L
          val next     = snapshotOf(sequence, observed, transferMemory, distributedConfig, replicationConfigured, config)
          hub.publish(Event(sequence, next)).as(next -> next)
        }
      }

    override val events: ZStream[Any, Nothing, Event] =
      ZStream.unwrapScoped {
        hub.subscribe.map { queue =>
          ZStream.fromZIO(state.get).flatMap { initial =>
            ZStream.succeed(Event(initial.sequence, initial)) ++
              ZStream.fromQueue(queue).filter(_.sequence > initial.sequence)
          }
        }
      }

  private def observe(
    runtimeHealth: RuntimeHealth,
    metrics: MetricsRegistry,
    transferBudget: TransferBudget,
    distributedAdmission: DistributedAdmission,
    distributedEnabled: Boolean,
  ): UIO[Observed] =
    for
      health      <- runtimeHealth.refresh
      snapshot    <- metrics.snapshot
      available   <- transferBudget.availableBytes
      distributed <-
        if distributedEnabled then
          distributedAdmission
            .snapshot(TransferScope.backend(StoreBackend.Runtime))
            .either
            .map(result => Some(result.map(_.occupancy)))
        else ZIO.none
      now         <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
    yield Observed(health, snapshot, available, distributed, now)

  private def snapshotOf(
    sequence: Long,
    observed: Observed,
    transferMemory: TransferMemoryConfig,
    distributedConfig: RedisAdmissionConfig,
    replicationConfigured: Boolean,
    config: Config,
  ): Snapshot =
    val values       = MetricValues(observed.metrics)
    val placement    = placementOf(observed.health)
    val capacity     = capacityOf(observed, values, transferMemory, distributedConfig)
    val durability   = durabilityOf(values, replicationConfigured)
    val dependencies = dependenciesOf(values)
    val traffic      = trafficOf(values)
    val checks       = List(
      storageCheck(observed.health),
      placementCheck(observed.health),
      pressureCheck(CheckId.LocalAdmission, "Local admission", capacity.localUsedPercent, config.pressureWarningPercent),
      distributedCheck(observed.distributed, capacity, config.pressureWarningPercent),
      durabilityCheck(durability),
      postgresCheck(dependencies, config.pressureWarningPercent),
    )
    val status       = overall(checks)
    Snapshot(
      sequence = sequence,
      observedAtEpochMillis = observed.observedAtEpochMillis,
      status = status,
      summary = summary(status, checks),
      checks = checks,
      placement = placement,
      capacity = capacity,
      durability = durability,
      dependencies = dependencies,
      traffic = traffic,
    )

  private def placementOf(health: RuntimeHealth.Snapshot): Placement =
    health.shardcake match
      case None          => Placement(false, PlacementStatus.SingleNode, None, 0, 0, 0, 1, 0)
      case Some(current) =>
        Placement(
          enabled = true,
          status = current.status match
            case ShardcakeHealth.Status.Starting    => PlacementStatus.Starting
            case ShardcakeHealth.Status.Healthy     => PlacementStatus.Healthy
            case ShardcakeHealth.Status.Rebalancing => PlacementStatus.Rebalancing
            case ShardcakeHealth.Status.Unassigned  => PlacementStatus.Unassigned
            case ShardcakeHealth.Status.Unavailable => PlacementStatus.Unavailable,
          nodeId = Some(current.node.id),
          configuredShards = current.configuredShards,
          assignedShards = current.assignedShards,
          localAssignedShards = current.localAssignedShards,
          observedNodes = current.observedNodes,
          trackedSessions = current.trackedSessions,
        )

  private def capacityOf(
    observed: Observed,
    values: MetricValues,
    transferMemory: TransferMemoryConfig,
    distributedConfig: RedisAdmissionConfig,
  ): Capacity =
    val localLimit     = transferMemory.maximumBufferedBytes.value
    val localAvailable = observed.localAvailableBytes.max(0L).min(localLimit)
    val occupancy      = observed.distributed.flatMap(_.toOption)
    val limits         = Option.when(distributedConfig.enabled)(distributedConfig.limits)
    Capacity(
      localLimitBytes = localLimit,
      localAvailableBytes = localAvailable,
      localUsedPercent = percent(localLimit - localAvailable, localLimit),
      distributedEnabled = distributedConfig.enabled,
      serviceBufferedBytes = occupancy.map(_.serviceBufferedBytes),
      serviceLimitBytes = limits.map(_.maximumServiceBufferedBytes.value),
      serviceUsedPercent = occupancy.zip(limits).map { case (current, limit) =>
        percent(current.serviceBufferedBytes, limit.maximumServiceBufferedBytes.value)
      },
      serviceTransfers = occupancy.map(_.serviceTransfers),
      serviceTransferLimit = limits.map(_.maximumConcurrentServiceTransfers.value),
      backendTransfers = occupancy.map(_.backendTransfers),
      backendTransferLimit = limits.map(_.maximumConcurrentBackendTransfers.value),
      admittedTransfers = values.counter(MetricKeys.TransferAdmissionTotal, Map("outcome" -> "admitted")),
      rejectedTransfers = values.counter(MetricKeys.TransferAdmissionTotal, Map("outcome" -> "rejected")),
      lostLeases = values.counter(MetricKeys.DistributedAdmissionLeaseLoss),
    )

  private def durabilityOf(values: MetricValues, configured: Boolean): Durability =
    val observed = values.has(MetricKeys.ReplicaRepairCyclesTotal) || values.has(MetricKeys.ReplicaHealthyTargets)
    Durability(
      repairConfigured = configured,
      repairObserved = observed,
      healthyTargets = values.gaugeLong(MetricKeys.ReplicaHealthyTargets),
      underProtectedBlocks = values.gaugeLong(MetricKeys.ReplicaUnderProtectedBlocks),
      failedBlocks = values.gaugeLong(MetricKeys.ReplicaRepairFailedBlocks),
      repairedCopies = values.counter(MetricKeys.ReplicaRepairsTotal),
      repairCursor = values.gaugeLong(MetricKeys.ReplicaRepairCursor),
      lastSuccessfulRepairEpochSeconds = values.gaugeOption(MetricKeys.ReplicaRepairLastSuccess).map(safeLong),
    )

  private def dependenciesOf(values: MetricValues): List[Dependency] =
    val postgresObserved = values.has(MetricKeys.PostgresPoolConnections) || values.has(MetricKeys.PostgresPoolAwaiting)
    val s3Observed       = values.has(MetricKeys.S3ApiCallsTotal)
    List(
      Dependency(
        kind = DependencyKind.PostgreSql,
        observed = postgresObserved,
        activeConnections = Option.when(postgresObserved)(values.gaugeLong(MetricKeys.PostgresPoolConnections, Map("state" -> "active"))),
        maximumConnections = Option.when(postgresObserved)(values.gaugeLong(MetricKeys.PostgresPoolConnections, Map("state" -> "maximum"))),
        awaitingConnections = Option.when(postgresObserved)(values.gaugeLong(MetricKeys.PostgresPoolAwaiting)),
        operations = values.counter(MetricKeys.BackendOperationsTotal, Map("backend" -> "pg")),
        failures = values.counter(MetricKeys.BackendFailures, Map("backend" -> "pg")),
        retries = 0L,
      ),
      Dependency(
        kind = DependencyKind.S3,
        observed = s3Observed,
        activeConnections = None,
        maximumConnections = None,
        awaitingConnections = None,
        operations = values.counter(MetricKeys.S3ApiCallsTotal),
        failures = values.counter(MetricKeys.S3ApiCallsTotal, Map("outcome" -> "failure")),
        retries = values.counter(MetricKeys.S3RetriesTotal),
      ),
    )

  private def trafficOf(values: MetricValues): Traffic =
    val freshBytes     = values.counter(MetricKeys.FreshBlockBytesTotal)
    val duplicateBytes = values.counter(MetricKeys.DuplicateBlockBytesTotal)
    Traffic(
      blobIngests = values.counter(MetricKeys.BlobIngestsTotal),
      bytesIngested = values.counter(MetricKeys.BytesIngestedTotal),
      freshBlocks = values.counter(MetricKeys.FreshBlocksTotal),
      duplicateBlocks = values.counter(MetricKeys.DuplicateBlocksTotal),
      freshBytes = freshBytes,
      duplicateBytes = duplicateBytes,
      byteReusePercent = percent(duplicateBytes, saturatingAdd(freshBytes, duplicateBytes)),
      httpRequests = values.counter(MetricKeys.HttpRequestsTotal),
      httpErrors = values.counter(MetricKeys.HttpErrorsTotal),
      backendOperations = values.counter(MetricKeys.BackendOperationsTotal),
      backendFailures = values.counter(MetricKeys.BackendFailures),
    )

  private def storageCheck(health: RuntimeHealth.Snapshot): Check =
    health.storage match
      case RuntimeHealth.StorageStatus.Ready       =>
        Check(CheckId.Storage, "Storage", CheckStatus.Ready, "Blob and upload stores responded")
      case RuntimeHealth.StorageStatus.Unavailable =>
        Check(CheckId.Storage, "Storage", CheckStatus.Unavailable, "Blob or upload storage did not respond")

  private def placementCheck(health: RuntimeHealth.Snapshot): Check =
    health.shardcake match
      case None          => Check(CheckId.Placement, "Shard placement", CheckStatus.Inactive, "Single-node routing")
      case Some(current) =>
        val status = current.status match
          case ShardcakeHealth.Status.Healthy     => CheckStatus.Ready
          case ShardcakeHealth.Status.Rebalancing => CheckStatus.Degraded
          case _                                  => CheckStatus.Unavailable
        Check(CheckId.Placement, "Shard placement", status, current.detail)

  private def pressureCheck(id: CheckId, label: String, usedPercent: Int, warningPercent: Int): Check =
    if usedPercent >= warningPercent then Check(id, label, CheckStatus.Degraded, s"$usedPercent% of configured capacity is reserved")
    else Check(id, label, CheckStatus.Ready, s"$usedPercent% of configured capacity is reserved")

  private def distributedCheck(
    probe: Option[Either[DistributedAdmission.Error, AdmissionOccupancy]],
    capacity: Capacity,
    warningPercent: Int,
  ): Check =
    probe match
      case None              =>
        Check(CheckId.DistributedAdmission, "Distributed admission", CheckStatus.Inactive, "Process-local admission")
      case Some(Left(error)) =>
        Check(CheckId.DistributedAdmission, "Distributed admission", CheckStatus.Unavailable, admissionErrorCode(error))
      case Some(Right(_))    =>
        val servicePressure  = capacity.serviceUsedPercent.getOrElse(0)
        val transferPressure = capacity.serviceTransfers
          .zip(capacity.serviceTransferLimit)
          .map { case (current, limit) => percent(current, limit.toLong) }
          .getOrElse(0)
        val pressure         = math.max(servicePressure, transferPressure)
        if pressure >= warningPercent then
          Check(CheckId.DistributedAdmission, "Distributed admission", CheckStatus.Degraded, s"$pressure% of a service limit is in use")
        else Check(CheckId.DistributedAdmission, "Distributed admission", CheckStatus.Ready, "Coordinator lease state is available")

  private def durabilityCheck(durability: Durability): Check =
    if !durability.repairConfigured then
      Check(CheckId.Durability, "Durability", CheckStatus.Inactive, "Replication repair is not configured")
    else if !durability.repairObserved then
      Check(CheckId.Durability, "Durability", CheckStatus.Degraded, "Waiting for the first repair observation")
    else if durability.underProtectedBlocks > 0L || durability.failedBlocks > 0L then
      Check(
        CheckId.Durability,
        "Durability",
        CheckStatus.Degraded,
        s"${durability.underProtectedBlocks} under-protected blocks; ${durability.failedBlocks} failed in the last cycle",
      )
    else Check(CheckId.Durability, "Durability", CheckStatus.Ready, "No under-protected blocks are reported")

  private def postgresCheck(dependencies: List[Dependency], warningPercent: Int): Check =
    dependencies.find(_.kind == DependencyKind.PostgreSql) match
      case None | Some(Dependency(_, false, _, _, _, _, _, _)) =>
        Check(CheckId.PostgreSql, "PostgreSQL", CheckStatus.Inactive, "No PostgreSQL pool is active")
      case Some(postgres)                                      =>
        val awaiting = postgres.awaitingConnections.getOrElse(0L)
        val pressure = postgres.activeConnections
          .zip(postgres.maximumConnections)
          .map { case (active, maximum) => percent(active, maximum) }
          .getOrElse(0)
        if awaiting > 0L || pressure >= warningPercent then
          Check(CheckId.PostgreSql, "PostgreSQL", CheckStatus.Degraded, s"$pressure% pool use; $awaiting requests waiting")
        else Check(CheckId.PostgreSql, "PostgreSQL", CheckStatus.Ready, s"$pressure% pool use; no requests waiting")

  private def overall(checks: List[Check]): Status =
    if checks.exists(_.status == CheckStatus.Unavailable) then Status.Unavailable
    else if checks.exists(_.status == CheckStatus.Degraded) then Status.Degraded
    else Status.Ready

  private def summary(status: Status, checks: List[Check]): String = status match
    case Status.Ready       => "All active operational checks are ready"
    case Status.Degraded    => s"${checks.count(_.status == CheckStatus.Degraded)} operational checks need attention"
    case Status.Unavailable => s"${checks.count(_.status == CheckStatus.Unavailable)} operational checks are unavailable"

  private def admissionErrorCode(error: DistributedAdmission.Error): String = error match
    case _: DistributedAdmission.Error.InvalidRequest => "Coordinator rejected the snapshot request"
    case _: DistributedAdmission.Error.Rejected       => "Coordinator is saturated"
    case _: DistributedAdmission.Error.TimedOut       => "Coordinator snapshot timed out"
    case _: DistributedAdmission.Error.Unavailable    => "Coordinator is unavailable"
    case _: DistributedAdmission.Error.LeaseLost      => "Coordinator lease ownership was lost"
    case _: DistributedAdmission.Error.Protocol       => "Coordinator returned an invalid response"

  private def percent(value: Long, total: Long): Int =
    if total <= 0L then 0
    else math.min(100, math.max(0, Math.round(value.toDouble / total.toDouble * 100.0).toInt))

  private def safeLong(value: Double): Long =
    if value.isNaN || value <= 0.0 then 0L
    else if value >= Long.MaxValue.toDouble then Long.MaxValue
    else value.toLong

  private def saturatingAdd(left: Long, right: Long): Long =
    if right > 0L && left > Long.MaxValue - right then Long.MaxValue else left + right

  private final case class MetricValues(snapshot: MetricsSnapshot):
    def has(name: String): Boolean =
      snapshot.counters.keysIterator.exists(_.name == name) || snapshot.gauges.keysIterator.exists(_.name == name)

    def counter(name: String, requiredTags: Map[String, String] = Map.empty): Long =
      snapshot.counters.iterator
        .collect {
          case (key, value) if key.name == name && requiredTags.forall { case (tag, expected) => key.tags.get(tag).contains(expected) } =>
            value
        }
        .foldLeft(0L)(saturatingAdd)

    def gaugeOption(name: String, requiredTags: Map[String, String] = Map.empty): Option[Double] =
      val values = snapshot.gauges.iterator.collect {
        case (key, value) if key.name == name && requiredTags.forall { case (tag, expected) => key.tags.get(tag).contains(expected) } =>
          value
      }.toList
      Option.when(values.nonEmpty)(values.filter(_.isFinite).sum)

    def gaugeLong(name: String, requiredTags: Map[String, String] = Map.empty): Long =
      gaugeOption(name, requiredTags).fold(0L)(safeLong)
