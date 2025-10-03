package graviton.backend.s3

import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import zio.UIO

final case class S3Metrics(registry: MetricsRegistry):
  def recordPut(bytes: Long): UIO[Unit] =
    registry.counter(MetricKeys.BytesIngested, Map("backend" -> "s3"))
