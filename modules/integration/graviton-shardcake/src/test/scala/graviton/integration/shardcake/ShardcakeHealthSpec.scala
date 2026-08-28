package graviton.integration.shardcake

import graviton.runtime.metrics.InMemoryMetricsRegistry
import graviton.runtime.upload.*
import zio.*
import zio.test.*

import java.time.Instant

object ShardcakeHealthSpec extends ZIOSpecDefault:
  private val local  = ShardcakeUploadConfig.Default.node
  private val remote = UploadNode.fromEndpoints(
    UploadNodeHost.applyUnsafe("remote.internal"),
    UploadNodePort.applyUnsafe(54331),
    UploadNodePort.applyUnsafe(54332),
  )
  private val config = ShardcakeUploadConfig.Default.copy(numberOfShards = 16)

  override def spec: Spec[TestEnvironment, Any] = suite("Shardcake health")(
    test("reports a complete assignment and bounded-cardinality metrics") {
      val assignments = Chunk.fromIterable((0 until 16).map { shard =>
        val node = if shard % 2 == 0 then local else remote
        UploadShardAssignment(shard, node.host, node.controlPort)
      })

      for
        _         <- TestClock.setTime(Instant.ofEpochMilli(42000L))
        placement  = fixedPlacement(assignments)
        hot       <- UploadHotState.inMemory()
        registry  <- InMemoryMetricsRegistry.make
        health    <- ShardcakeHealth.make(config, placement, hot, registry)
        snapshot  <- health.refresh
        ready     <- health.readiness.exit
        metrics   <- registry.snapshot
        healthKeys = metrics.counters.keySet ++ metrics.gauges.keySet
      yield assertTrue(
        snapshot.status == ShardcakeHealth.Status.Healthy,
        snapshot.ready,
        snapshot.assignedShards == 16,
        snapshot.localAssignedShards == 8,
        snapshot.observedNodes == 2,
        snapshot.checkedAtMillis.contains(42000L),
        ready.isSuccess,
        healthKeys.forall(key => key.tags.keySet.subsetOf(Set("status"))),
        healthKeys.forall(key => !key.tags.values.exists(value => value == local.id.value || value == remote.id.value)),
      )
    },
    test("keeps readiness during a partial rebalance") {
      val assignments = Chunk.fromIterable((0 until 8).map(shard => UploadShardAssignment(shard, local.host, local.controlPort)))

      for
        hot      <- UploadHotState.inMemory()
        registry <- InMemoryMetricsRegistry.make
        health   <- ShardcakeHealth.make(config, fixedPlacement(assignments), hot, registry)
        snapshot <- health.refresh
        ready    <- health.readiness.exit
      yield assertTrue(
        snapshot.status == ShardcakeHealth.Status.Rebalancing,
        snapshot.ready,
        snapshot.assignedShards == 8,
        ready.isSuccess,
      )
    },
    test("fails readiness when this node owns no shard") {
      val assignments = Chunk.fromIterable((0 until 16).map(shard => UploadShardAssignment(shard, remote.host, remote.controlPort)))

      for
        hot      <- UploadHotState.inMemory()
        registry <- InMemoryMetricsRegistry.make
        health   <- ShardcakeHealth.make(config, fixedPlacement(assignments), hot, registry)
        snapshot <- health.refresh
        ready    <- health.readiness.exit
      yield assertTrue(
        snapshot.status == ShardcakeHealth.Status.Unassigned,
        !snapshot.ready,
        ready.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[ShardcakeNode.HealthError.LocalNodeUnassigned]),
      )
    },
    test("preserves the last successful check when placement becomes unavailable") {
      val assigned = Chunk.fromIterable((0 until 16).map(shard => UploadShardAssignment(shard, local.host, local.controlPort)))

      for
        _        <- TestClock.setTime(Instant.ofEpochMilli(1000L))
        values   <- Ref.make[Either[UploadPlacement.Error, Chunk[UploadShardAssignment]]](Right(assigned))
        placement = refPlacement(values)
        hot      <- UploadHotState.inMemory()
        registry <- InMemoryMetricsRegistry.make
        health   <- ShardcakeHealth.make(config, placement, hot, registry)
        first    <- health.refresh
        _        <- TestClock.setTime(Instant.ofEpochMilli(2000L))
        _        <- values.set(Left(UploadPlacement.Error.BackendFailure("assignments", new RuntimeException("offline"))))
        second   <- health.refresh
      yield assertTrue(
        first.status == ShardcakeHealth.Status.Healthy,
        second.status == ShardcakeHealth.Status.Unavailable,
        second.checkedAtMillis.contains(2000L),
        second.lastSuccessfulAtMillis.contains(1000L),
      )
    },
  )

  private def fixedPlacement(snapshotAssignments: Chunk[UploadShardAssignment]): UploadPlacement =
    new UploadPlacement:
      override val localNode: UIO[UploadNode]                                           = ZIO.succeed(local)
      override def locate(key: UploadSessionKey): IO[UploadPlacement.Error, UploadNode] = ZIO.succeed(local)
      override val assignments: IO[UploadPlacement.Error, Chunk[UploadShardAssignment]] = ZIO.succeed(snapshotAssignments)

  private def refPlacement(
    values: Ref[Either[UploadPlacement.Error, Chunk[UploadShardAssignment]]]
  ): UploadPlacement =
    new UploadPlacement:
      override val localNode: UIO[UploadNode]                                           = ZIO.succeed(local)
      override def locate(key: UploadSessionKey): IO[UploadPlacement.Error, UploadNode] = ZIO.succeed(local)
      override val assignments: IO[UploadPlacement.Error, Chunk[UploadShardAssignment]] = values.get.absolve
