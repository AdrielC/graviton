package graviton.server.metrics

import graviton.runtime.metrics.MetricsRegistry
import graviton.runtime.metrics.PrometheusTextRenderer
import zio.*

final case class PrometheusExporter(registry: MetricsRegistry):
  def render: UIO[String] =
    registry.snapshot.map(PrometheusTextRenderer.render)
