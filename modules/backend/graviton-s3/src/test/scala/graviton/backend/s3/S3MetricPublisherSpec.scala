package graviton.backend.s3

import graviton.runtime.metrics.{InMemoryMetricsRegistry, MetricKeys}
import software.amazon.awssdk.core.metrics.CoreMetric
import software.amazon.awssdk.metrics.MetricCollector
import zio.*
import zio.test.*

object S3MetricPublisherSpec extends ZIOSpecDefault:
  override def spec =
    suite("S3MetricPublisher")(
      test("exports bounded operation outcomes and SDK retry counts") {
        for
          metrics  <- InMemoryMetricsRegistry.make
          collector = MetricCollector.create("ApiCall")
          _         = collector.reportMetric(CoreMetric.OPERATION_NAME, "PutObject")
          _         = collector.reportMetric(CoreMetric.API_CALL_SUCCESSFUL, true)
          _         = collector.reportMetric(CoreMetric.RETRY_COUNT, 2)
          snapshot <- ZIO.scoped {
                        for
                          publisher <- S3ClientLayer.S3MetricPublisher.scoped(metrics)
                          _          = publisher.publish(collector.collect())
                          value     <- metrics.snapshot
                                         .repeatUntil(_.counters.keys.exists(_.name == MetricKeys.S3RetriesTotal))
                        yield value
                      }
          tags      = Map("operation" -> "PutObject", "outcome" -> "success")
        yield assertTrue(
          snapshot.counters.exists { case (key, value) => key.name == MetricKeys.S3ApiCallsTotal && key.tags == tags && value == 1L },
          snapshot.counters.exists { case (key, value) => key.name == MetricKeys.S3RetriesTotal && key.tags == tags && value == 2L },
        )
      } @@ TestAspect.timeout(5.seconds),
      test("never blocks SDK callbacks and reports bounded-queue overflow") {
        for
          metrics  <- InMemoryMetricsRegistry.make
          collector = MetricCollector.create("ApiCall")
          _         = collector.reportMetric(CoreMetric.OPERATION_NAME, "GetObject")
          snapshot <- ZIO.scoped {
                        for
                          publisher <- S3ClientLayer.S3MetricPublisher.scoped(metrics, capacity = 1)
                          _          = (1 to 10000).foreach(_ => publisher.publish(collector.collect()))
                          value     <- metrics.snapshot
                                         .repeatUntil(_.counters.keys.exists(_.name == MetricKeys.S3MetricEventsDroppedTotal))
                        yield value
                      }
          dropped   = snapshot.counters.collect { case (key, value) if key.name == MetricKeys.S3MetricEventsDroppedTotal => value }.sum
        yield assertTrue(dropped > 0L)
      } @@ TestAspect.timeout(5.seconds),
    )
