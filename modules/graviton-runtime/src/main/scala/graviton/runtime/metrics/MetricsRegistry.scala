package graviton.runtime.metrics

import zio.*

trait MetricsRegistry:
  def counter(name: String, tags: Map[String, String]): UIO[Unit]
  def gauge(name: String, value: Double, tags: Map[String, String]): UIO[Unit]

  /**
   * Best-effort snapshot for exporters (e.g. Prometheus).
   *
   * Implementations that do not support scraping can return empty snapshots.
   */
  def snapshot: UIO[MetricsSnapshot] = ZIO.succeed(MetricsSnapshot.empty)

final case class MetricsSnapshot(
  counters: Map[MetricKey, Long],
  gauges: Map[MetricKey, Double],
)

object MetricsSnapshot:
  val empty: MetricsSnapshot = MetricsSnapshot(counters = Map.empty, gauges = Map.empty)

final case class MetricKey(name: String, tags: Map[String, String]):
  def stableTags: List[(String, String)] = tags.toList.sortBy(_._1)
