package graviton.server

import graviton.integration.shardcake.ShardcakeNode
import graviton.runtime.Graviton
import graviton.runtime.metrics.{InMemoryMetricsRegistry, MetricKeys, MetricsRegistry}
import graviton.runtime.stores.BlobStore
import zio.*
import zio.test.*

import java.time.Instant

object RuntimeHealthSpec extends ZIOSpecDefault:
  override def spec: Spec[TestEnvironment, Any] = suite("Runtime health")(
    test("combines storage readiness with process-local ingest and locality counters") {
      for
        _        <- TestClock.setTime(Instant.ofEpochMilli(73000L))
        graviton <- Graviton.inMemory()
        registry <- InMemoryMetricsRegistry.make
        _        <- registry.counterBy(MetricKeys.BlobIngestsTotal, 3L, Map("backend" -> "cas"))
        _        <- registry.counterBy(MetricKeys.BytesIngestedTotal, 4096L, Map("backend" -> "cas"))
        _        <- registry.counterBy(MetricKeys.FreshBlocksTotal, 5L, Map("backend" -> "cas"))
        _        <- registry.counterBy(MetricKeys.DuplicateBlocksTotal, 15L, Map("backend" -> "cas"))
        _        <- registry.counterBy(MetricKeys.UploadLocalityDecisionsTotal, 2L, Map("route" -> "local"))
        _        <- registry.counterBy(MetricKeys.UploadLocalityDecisionsTotal, 1L, Map("route" -> "remote"))
        snapshot <- RuntimeHealth.refresh.provide(
                      ZLayer.succeed[BlobStore](graviton.blobStore),
                      ZLayer.succeed[MetricsRegistry](registry),
                      ZLayer.succeed[Option[ShardcakeNode]](None),
                      ZLayer.succeed(RuntimeHealth.Config.Default),
                      RuntimeHealth.live,
                    )
      yield assertTrue(
        snapshot.ready,
        snapshot.storage == RuntimeHealth.StorageStatus.Ready,
        snapshot.shardcake.isEmpty,
        snapshot.checkedAtMillis == 73000L,
        snapshot.process.blobIngests == 3L,
        snapshot.process.bytesIngested == 4096L,
        snapshot.process.totalBlocks == 20L,
        snapshot.process.reuseRatio == 0.75,
        snapshot.process.localRoutes == 2L,
        snapshot.process.remoteRoutes == 1L,
      )
    },
    test("rejects a non-positive configured health timeout") {
      val provider = ConfigProvider.fromMap(Map("graviton.health.check-timeout" -> "0s"))
      for exit <- ZIO.withConfigProvider(provider)(ZIO.config(RuntimeHealth.Config.config)).exit
      yield assertTrue(exit.isFailure)
    },
  )
