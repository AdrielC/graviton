package graviton.server.operations

import graviton.integration.redis.RedisAdmissionConfig
import graviton.runtime.admission.*
import graviton.runtime.config.{TransferAdmissionConfig, TransferMemoryConfig}
import graviton.runtime.metrics.{InMemoryMetricsRegistry, MetricKeys}
import graviton.runtime.stores.{TransferBudget, TransferScope}
import graviton.runtime.upload.UploadNodeId
import graviton.server.RuntimeHealth
import graviton.shared.ApiJson
import zio.*
import zio.test.*

import java.time.Instant

object OperationsSpec extends ZIOSpecDefault:
  override def spec: Spec[TestEnvironment, Any] = suite("Operations")(
    test("reports a bounded ready snapshot for an empty single-node store") {
      ZIO.scoped {
        for
          _        <- TestClock.setTime(Instant.ofEpochMilli(12000L))
          metrics  <- InMemoryMetricsRegistry.make
          budget   <- TransferBudget.make(TransferMemoryConfig.Default, TransferAdmissionConfig.Default)
          service  <- Operations.make(
                        readyHealth,
                        metrics,
                        budget,
                        TransferMemoryConfig.Default,
                        DistributedAdmission.disabled,
                        RedisAdmissionConfig.Default,
                        false,
                        Operations.Config.Default.copy(refreshInterval = 1.hour),
                      )
          snapshot <- service.current
          first    <- service.events.take(1).runHead
          encoded   = ApiJson.encode(snapshot)
          decoded  <- ZIO.fromEither(ApiJson.decode[Operations.Snapshot](encoded))
          node      = UploadNodeId.applyUnsafe("node-a")
          withNode  = ApiJson.encode(snapshot.copy(placement = snapshot.placement.copy(nodeId = Some(node))))
          invalid   = ApiJson.decode[Operations.Snapshot](withNode.replace("node-a", "not valid!"))
        yield assertTrue(
          snapshot.sequence == 1L,
          snapshot.observedAtEpochMillis == 12000L,
          snapshot.status == Operations.Status.Ready,
          snapshot.capacity.localAvailableBytes == snapshot.capacity.localLimitBytes,
          snapshot.checks.size == 6,
          snapshot.checks.exists(check =>
            check.id == Operations.CheckId.DistributedAdmission && check.status == Operations.CheckStatus.Inactive
          ),
          first.exists(_.snapshot == snapshot),
          decoded == snapshot,
          invalid.isLeft,
          !encoded.contains("tenantId"),
        )
      }
    },
    test("derives degraded capacity, durability, and dependency checks from bounded metrics") {
      ZIO.scoped {
        for
          metrics  <- InMemoryMetricsRegistry.make
          _        <- metrics.gauge(MetricKeys.ReplicaHealthyTargets, 2.0, Map.empty)
          _        <- metrics.gauge(MetricKeys.ReplicaUnderProtectedBlocks, 3.0, Map.empty)
          _        <- metrics.gauge(MetricKeys.ReplicaRepairFailedBlocks, 1.0, Map.empty)
          _        <- metrics.counterBy(MetricKeys.ReplicaRepairCyclesTotal, 1L, Map("outcome" -> "degraded"))
          _        <- metrics.gauge(MetricKeys.PostgresPoolConnections, 9.0, Map("pool" -> "primary", "state" -> "active"))
          _        <- metrics.gauge(MetricKeys.PostgresPoolConnections, 10.0, Map("pool" -> "primary", "state" -> "maximum"))
          _        <- metrics.gauge(MetricKeys.PostgresPoolAwaiting, 2.0, Map("pool" -> "primary"))
          _        <- metrics.counterBy(MetricKeys.S3ApiCallsTotal, 7L, Map("operation" -> "PutObject", "outcome" -> "success"))
          _        <- metrics.counterBy(MetricKeys.S3RetriesTotal, 2L, Map("operation" -> "PutObject", "outcome" -> "success"))
          _        <- metrics.counterBy(MetricKeys.FreshBlockBytesTotal, 1024L, Map.empty)
          _        <- metrics.counterBy(MetricKeys.DuplicateBlockBytesTotal, 3072L, Map.empty)
          budget   <- TransferBudget.make(TransferMemoryConfig.Default, TransferAdmissionConfig.Default)
          service  <- Operations.make(
                        readyHealth,
                        metrics,
                        budget,
                        TransferMemoryConfig.Default,
                        DistributedAdmission.disabled,
                        RedisAdmissionConfig.Default,
                        true,
                        Operations.Config.Default.copy(refreshInterval = 1.hour),
                      )
          snapshot <- service.current
        yield assertTrue(
          snapshot.status == Operations.Status.Degraded,
          snapshot.traffic.byteReusePercent == 75,
          snapshot.durability.underProtectedBlocks == 3L,
          snapshot.dependencies.find(_.kind == Operations.DependencyKind.PostgreSql).flatMap(_.awaitingConnections).contains(2L),
          snapshot.dependencies.find(_.kind == Operations.DependencyKind.S3).exists(_.retries == 2L),
          snapshot.checks.exists(check => check.id == Operations.CheckId.Durability && check.status == Operations.CheckStatus.Degraded),
          snapshot.checks.exists(check => check.id == Operations.CheckId.PostgreSql && check.status == Operations.CheckStatus.Degraded),
        )
      }
    },
    test("fails the operational state closed when the distributed coordinator cannot be observed") {
      ZIO.scoped {
        for
          metrics  <- InMemoryMetricsRegistry.make
          budget   <- TransferBudget.make(TransferMemoryConfig.Default, TransferAdmissionConfig.Default)
          service  <- Operations.make(
                        readyHealth,
                        metrics,
                        budget,
                        TransferMemoryConfig.Default,
                        unavailableAdmission,
                        RedisAdmissionConfig.Default.copy(enabled = true),
                        false,
                        Operations.Config.Default.copy(refreshInterval = 1.hour),
                      )
          snapshot <- service.current
        yield assertTrue(
          snapshot.status == Operations.Status.Unavailable,
          snapshot.capacity.distributedEnabled,
          snapshot.capacity.serviceBufferedBytes.isEmpty,
          snapshot.checks.exists(check =>
            check.id == Operations.CheckId.DistributedAdmission &&
              check.status == Operations.CheckStatus.Unavailable &&
              check.detail == "Coordinator is unavailable"
          ),
        )
      }
    },
    test("publishes refreshes under TestClock without scheduler-yield assumptions") {
      ZIO.scoped {
        for
          metrics <- InMemoryMetricsRegistry.make
          budget  <- TransferBudget.make(TransferMemoryConfig.Default, TransferAdmissionConfig.Default)
          service <- Operations.make(
                       readyHealth,
                       metrics,
                       budget,
                       TransferMemoryConfig.Default,
                       DistributedAdmission.disabled,
                       RedisAdmissionConfig.Default,
                       false,
                       Operations.Config.Default.copy(refreshInterval = 1.second, eventCapacity = 2),
                     )
          fiber   <- service.events.take(2).runCollect.fork
          _       <- TestClock.adjust(1.second)
          events  <- fiber.join
        yield assertTrue(events.map(_.sequence) == Chunk(1L, 2L))
      }
    },
    test("validates event capacity, pressure threshold, and refresh interval") {
      val invalid = List(
        Map("graviton.operations.event-capacity"           -> "0"),
        Map("graviton.operations.pressure-warning-percent" -> "101"),
        Map("graviton.operations.refresh-interval"         -> "0s"),
      )
      ZIO
        .foreach(invalid) { values =>
          ZIO.withConfigProvider(ConfigProvider.fromMap(values))(ZIO.config(Operations.Config.config)).exit
        }
        .map(exits => assertTrue(exits.forall(_.isFailure)))
    },
  )

  private val readyHealth: RuntimeHealth = new RuntimeHealth:
    override val refresh: UIO[RuntimeHealth.Snapshot] =
      Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS).map { now =>
        RuntimeHealth.Snapshot(
          storage = RuntimeHealth.StorageStatus.Ready,
          shardcake = None,
          process = RuntimeHealth.ProcessMetrics(0L, 0L, 0L, 0L, 0L, 0L, 0L),
          checkedAtMillis = now,
        )
      }

  private val unavailableAdmission: DistributedAdmission = new DistributedAdmission:
    override def acquireScoped(
      request: DistributedAdmissionRequest
    ): ZIO[Scope, DistributedAdmission.Error, DistributedAdmissionLease] =
      ZIO.fail(DistributedAdmission.Error.Unavailable("test"))

    override def snapshot(scope: TransferScope): IO[DistributedAdmission.Error, AdmissionSnapshot] =
      ZIO.fail(DistributedAdmission.Error.Unavailable("test"))
