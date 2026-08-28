package graviton.backend.s3

import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import zio.UIO

final case class S3Metrics(registry: MetricsRegistry):
  private val putTags = Map("backend" -> "s3", "operation" -> "put", "direction" -> "write")

  def recordPut(bytes: Long): UIO[Unit] =
    registry.counter(MetricKeys.BackendOperationsTotal, putTags) *>
      registry.counterBy(MetricKeys.BackendBytesTransferredTotal, bytes.max(0L), putTags)

  def recordPutDuration(seconds: Double): UIO[Unit] =
    registry.histogram(MetricKeys.BackendOperationDuration, seconds, putTags)

  def recordFailure(): UIO[Unit] =
    registry.counter(MetricKeys.BackendFailures, putTags)
