package graviton.backend.s3

import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import zio.UIO

final case class S3Metrics(registry: MetricsRegistry):
  def recordPut(bytes: Long): UIO[Unit] =
    registry.gauge(MetricKeys.BytesIngested, bytes.toDouble, Map("backend" -> "s3"))
