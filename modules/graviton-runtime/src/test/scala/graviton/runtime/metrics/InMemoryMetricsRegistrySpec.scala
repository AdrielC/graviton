package graviton.runtime.metrics

import zio.*
import zio.test.*

object InMemoryMetricsRegistrySpec extends ZIOSpecDefault:

  override def spec =
    suite("InMemoryMetricsRegistry")(
      test("counter increments") {
        for
          reg  <- InMemoryMetricsRegistry.make
          _    <- reg.counter("req", Map("op" -> "get"))
          _    <- reg.counter("req", Map("op" -> "get"))
          _    <- reg.counter("req", Map("op" -> "get"))
          snap <- reg.snapshot
          key   = MetricKey("req", Map("op" -> "get"))
        yield assertTrue(snap.counters.get(key).contains(3L))
      },
      test("counters with different tags are independent") {
        for
          reg  <- InMemoryMetricsRegistry.make
          _    <- reg.counter("req", Map("op" -> "get"))
          _    <- reg.counter("req", Map("op" -> "put"))
          snap <- reg.snapshot
        yield assertTrue(
          snap.counters.get(MetricKey("req", Map("op" -> "get"))).contains(1L),
          snap.counters.get(MetricKey("req", Map("op" -> "put"))).contains(1L),
        )
      },
      test("gauge overwrites previous value") {
        for
          reg  <- InMemoryMetricsRegistry.make
          _    <- reg.gauge("temp", 100.0, Map.empty)
          _    <- reg.gauge("temp", 200.0, Map.empty)
          snap <- reg.snapshot
          key   = MetricKey("temp", Map.empty)
        yield assertTrue(snap.gauges.get(key).contains(200.0))
      },
      test("snapshot is initially empty") {
        for
          reg  <- InMemoryMetricsRegistry.make
          snap <- reg.snapshot
        yield assertTrue(
          snap.counters.isEmpty,
          snap.gauges.isEmpty,
        )
      },
      test("counters and gauges coexist in snapshot") {
        for
          reg  <- InMemoryMetricsRegistry.make
          _    <- reg.counter("c", Map.empty)
          _    <- reg.gauge("g", 3.14, Map.empty)
          snap <- reg.snapshot
        yield assertTrue(
          snap.counters.size == 1,
          snap.gauges.size == 1,
        )
      },
      test("concurrent counter increments are safe") {
        for
          reg  <- InMemoryMetricsRegistry.make
          _    <- ZIO.foreachParDiscard(1 to 100) { _ =>
                    reg.counter("concurrent", Map.empty)
                  }
          snap <- reg.snapshot
          key   = MetricKey("concurrent", Map.empty)
        yield assertTrue(snap.counters.get(key).contains(100L))
      },
      test("noop counter is a no-op") {
        for _ <- MetricsRegistry.noop.counter("x", Map.empty)
        yield assertTrue(true)
      },
      test("noop gauge is a no-op") {
        for _ <- MetricsRegistry.noop.gauge("x", 1.0, Map.empty)
        yield assertTrue(true)
      },
      test("noop snapshot is empty") {
        for snap <- MetricsRegistry.noop.snapshot
        yield assertTrue(snap == MetricsSnapshot.empty)
      },
      test("MetricKey equality is tag-order independent") {
        val k1 = MetricKey("name", Map("a" -> "1", "b" -> "2"))
        val k2 = MetricKey("name", Map("b" -> "2", "a" -> "1"))
        assertTrue(k1 == k2)
      },
      test("MetricKey stableTags are sorted") {
        val k = MetricKey("name", Map("z" -> "1", "a" -> "2", "m" -> "3"))
        assertTrue(k.stableTags == List("a" -> "2", "m" -> "3", "z" -> "1"))
      },
    )
