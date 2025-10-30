package graviton.backend.rocks

import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import zio.UIO

final case class RocksMetrics(registry: MetricsRegistry):
  def recordCompaction(): UIO[Unit] = registry.counter(MetricKeys.BackendFailures, Map("backend" -> "rocks"))
