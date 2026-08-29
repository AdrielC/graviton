package graviton.server.metrics

import graviton.runtime.metrics.{MetricKey, MetricsRegistry}
import zio.*
import zio.metrics.connectors.MetricsConfig
import zio.metrics.connectors.prometheus
import zio.test.*

object ZioMetricsRegistrySpec extends ZIOSpecDefault:
  override def spec: Spec[TestEnvironment, Any] = suite("ZIO metrics registry")(
    test("mirrors the console snapshot and publishes native ZIO metrics") {
      val counterName   = "graviton_test_native_counter_total"
      val histogramName = "graviton_test_native_duration_seconds"
      val tags          = Map("outcome" -> "ok")

      for
        registry <- ZIO.service[MetricsRegistry]
        _        <- registry.counterBy(counterName, 7L, tags)
        _        <- registry.histogram(histogramName, 0.25, tags)
        _        <- TestClock.adjust(2.seconds)
        snapshot <- registry.snapshot
        rendered <- registry.prometheus
      yield assertTrue(
        snapshot.counters.get(MetricKey(counterName, tags)).contains(7L),
        snapshot.gauges.get(MetricKey(histogramName, tags)).contains(0.25),
        rendered.contains(counterName),
        rendered.contains(histogramName),
        rendered.contains("outcome=\"ok\""),
      )
    }.provide(
      ZLayer.succeed(MetricsConfig(1.second)),
      prometheus.publisherLayer,
      prometheus.prometheusLayer,
      ZioMetricsRegistry.layer,
    ) @@ TestAspect.timeout(10.seconds)
  )
