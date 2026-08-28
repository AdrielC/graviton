package graviton.runtime.metrics

import zio.*

final class InMemoryMetricsRegistry private (
  countersRef: Ref[Map[MetricKey, Long]],
  gaugesRef: Ref[Map[MetricKey, Double]],
) extends MetricsRegistry:

  override def counterBy(name: String, delta: Long, tags: Map[String, String]): UIO[Unit] =
    if delta <= 0L then ZIO.unit
    else
      countersRef.update { m =>
        val key = MetricKey(name, tags)
        val n   = m.getOrElse(key, 0L)
        m.updated(key, if n > Long.MaxValue - delta then Long.MaxValue else n + delta)
      }.unit

  override def gauge(name: String, value: Double, tags: Map[String, String]): UIO[Unit] =
    gaugesRef.update(_.updated(MetricKey(name, tags), value)).unit

  override def histogram(name: String, value: Double, tags: Map[String, String]): UIO[Unit] =
    gauge(name, value, tags)

  override def snapshot: UIO[MetricsSnapshot] =
    countersRef.get.zipWith(gaugesRef.get)((c, g) => MetricsSnapshot(counters = c, gauges = g))

object InMemoryMetricsRegistry:
  val layer: ULayer[MetricsRegistry] =
    ZLayer.fromZIO {
      for
        c <- Ref.make(Map.empty[MetricKey, Long])
        g <- Ref.make(Map.empty[MetricKey, Double])
      yield InMemoryMetricsRegistry(c, g)
    }

  def make: UIO[InMemoryMetricsRegistry] =
    for
      c <- Ref.make(Map.empty[MetricKey, Long])
      g <- Ref.make(Map.empty[MetricKey, Double])
    yield InMemoryMetricsRegistry(c, g)
