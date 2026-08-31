package graviton.server.metrics

import graviton.runtime.metrics.{MetricKey, MetricsRegistry, MetricsSnapshot}
import zio.*
import zio.metrics.{Metric, MetricKeyType, MetricLabel}
import zio.metrics.connectors.prometheus.PrometheusPublisher

/**
 * Records Graviton metrics in ZIO's native metric registry while retaining the
 * small process snapshot used by the operator console and public stats API.
 */
final class ZioMetricsRegistry private (
  countersRef: Ref[Map[MetricKey, Long]],
  gaugesRef: Ref[Map[MetricKey, Double]],
  publisher: PrometheusPublisher,
  baseTags: Map[String, String],
) extends MetricsRegistry:

  override def counterBy(name: String, delta: Long, tags: Map[String, String]): UIO[Unit] =
    if delta <= 0L then ZIO.unit
    else
      val allTags = baseTags ++ tags
      countersRef
        .update { values =>
          val key = MetricKey(name, allTags)
          values.updated(key, saturatingAdd(values.getOrElse(key, 0L), delta))
        }
        .zipRight(tagged(Metric.counter(name), allTags).incrementBy(delta))

  override def gauge(name: String, value: Double, tags: Map[String, String]): UIO[Unit] =
    val allTags = baseTags ++ tags
    gaugesRef
      .update(_.updated(MetricKey(name, allTags), value))
      .zipRight(tagged(Metric.gauge(name), allTags).set(value))

  override def histogram(name: String, value: Double, tags: Map[String, String]): UIO[Unit] =
    val allTags = baseTags ++ tags
    gaugesRef
      .update(_.updated(MetricKey(name, allTags), value))
      .zipRight(tagged(Metric.histogram(name, ZioMetricsRegistry.DurationBoundaries), allTags).update(value))

  override def snapshot: UIO[MetricsSnapshot] =
    countersRef.get.zipWith(gaugesRef.get)(MetricsSnapshot.apply)

  override def prometheus: UIO[String] =
    publisher.get

  private def tagged[Type <: MetricKeyType, In, Out](
    metric: Metric[Type, In, Out],
    tags: Map[String, String],
  ): Metric[Type, In, Out] =
    metric.tagged(tags.iterator.map(MetricLabel.apply).toSet)

  private def saturatingAdd(left: Long, right: Long): Long =
    if left > Long.MaxValue - right then Long.MaxValue else left + right

object ZioMetricsRegistry:
  private val DurationBoundaries =
    MetricKeyType.Histogram.Boundaries.exponential(start = 0.001, factor = 2.0, count = 18)

  val layer: ZLayer[PrometheusPublisher, Nothing, MetricsRegistry] = layerWithTags(Map.empty)

  def layerWithTags(baseTags: Map[String, String]): ZLayer[PrometheusPublisher, Nothing, MetricsRegistry] =
    ZLayer.fromZIO {
      for
        counters  <- Ref.make(Map.empty[MetricKey, Long])
        gauges    <- Ref.make(Map.empty[MetricKey, Double])
        publisher <- ZIO.service[PrometheusPublisher]
      yield ZioMetricsRegistry(counters, gauges, publisher, baseTags)
    }
