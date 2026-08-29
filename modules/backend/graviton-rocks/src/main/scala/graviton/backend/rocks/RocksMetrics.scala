package graviton.backend.rocks

import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import zio.UIO

final case class RocksMetrics(registry: MetricsRegistry):
  private val compactionTags = Map("backend" -> "rocks", "operation" -> "compaction")

  def recordCompaction(): UIO[Unit] =
    registry.counter(MetricKeys.BackendMaintenanceTotal, compactionTags)

  def recordCompactionDuration(seconds: Double): UIO[Unit] =
    registry.histogram(MetricKeys.BackendOperationDuration, seconds, compactionTags)

  def recordFailure(): UIO[Unit] =
    registry.counter(MetricKeys.BackendFailures, compactionTags)
