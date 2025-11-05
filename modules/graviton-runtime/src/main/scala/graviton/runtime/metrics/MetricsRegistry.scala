package graviton.runtime.metrics

import zio.UIO

trait MetricsRegistry:
  def counter(name: String, tags: Map[String, String]): UIO[Unit]
  def gauge(name: String, value: Double, tags: Map[String, String]): UIO[Unit]
