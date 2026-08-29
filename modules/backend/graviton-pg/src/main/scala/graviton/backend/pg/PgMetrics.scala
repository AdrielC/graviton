package graviton.backend.pg

import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import zio.UIO

final case class PgMetrics(registry: MetricsRegistry):
  private val queryTags = Map("backend" -> "pg", "operation" -> "query")

  def recordQuery(): UIO[Unit] =
    registry.counter(MetricKeys.BackendOperationsTotal, queryTags)

  def recordQueryDuration(seconds: Double): UIO[Unit] =
    registry.histogram(MetricKeys.BackendOperationDuration, seconds, queryTags)

  def recordFailure(): UIO[Unit] =
    registry.counter(MetricKeys.BackendFailures, queryTags)
