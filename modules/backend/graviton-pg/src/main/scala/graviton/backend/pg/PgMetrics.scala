package graviton.backend.pg

import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import zio.UIO

final case class PgMetrics(registry: MetricsRegistry):
  def recordQuery(): UIO[Unit] = registry.counter(MetricKeys.BackendFailures, Map("backend" -> "pg"))
