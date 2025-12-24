package graviton.runtime.metrics

import zio.*

final class InMemoryMetricsRegistry private (
  countersRef: Ref[Map[MetricKey, Long]],
  gaugesRef: Ref[Map[MetricKey, Double]],
) extends MetricsRegistry:

  override def counter(name: String, tags: Map[String, String]): UIO[Unit] =
    countersRef.update { m =>
      val key = MetricKey(name, tags)
      val n   = m.getOrElse(key, 0L)
      m.updated(key, n + 1L)
    }.unit

  override def gauge(name: String, value: Double, tags: Map[String, String]): UIO[Unit] =
    gaugesRef.update(_.updated(MetricKey(name, tags), value)).unit

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
