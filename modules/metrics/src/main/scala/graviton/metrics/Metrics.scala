package graviton.metrics

import zio.*
import zio.metrics.*
import zio.metrics.connectors.prometheus.{
  PrometheusPublisher,
  prometheusLayer,
  publisherLayer
}
import zio.metrics.connectors.MetricsConfig
import java.time.Duration

object Metrics:
  val putCount: Metric.Counter[Int] = Metric.counterInt("graviton_put_total")
  val putLatency = Metric.timer(
    "graviton_put_latency_seconds",
    java.time.temporal.ChronoUnit.SECONDS
  )
  val getCount: Metric.Counter[Int] = Metric.counterInt("graviton_get_total")
  val getLatency = Metric.timer(
    "graviton_get_latency_seconds",
    java.time.temporal.ChronoUnit.SECONDS
  )
  val deleteCount: Metric.Counter[Int] =
    Metric.counterInt("graviton_delete_total")
  val deleteLatency = Metric.timer(
    "graviton_delete_latency_seconds",
    java.time.temporal.ChronoUnit.SECONDS
  )

  val prometheus: ULayer[PrometheusPublisher] = publisherLayer

  val prometheusUpdater: ZLayer[PrometheusPublisher, Nothing, Unit] =
    ZLayer.succeed(MetricsConfig(Duration.ofSeconds(5))) >>>
      prometheusLayer

  def scrape: ZIO[PrometheusPublisher, Nothing, String] =
    ZIO.serviceWithZIO[PrometheusPublisher](_.get)
