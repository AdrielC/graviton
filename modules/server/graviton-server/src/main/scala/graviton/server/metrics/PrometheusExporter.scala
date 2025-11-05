package graviton.server.metrics

import graviton.runtime.metrics.MetricsRegistry
import zio.ZIO

final case class PrometheusExporter(registry: MetricsRegistry):
  def render: ZIO[Any, Nothing, String] = ZIO.succeed("# prometheus metrics placeholder")
