package graviton.backend.s3

import graviton.runtime.metrics.{InMemoryMetricsRegistry, MetricKeys}
import software.amazon.awssdk.core.metrics.CoreMetric
import software.amazon.awssdk.metrics.MetricCollector
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
          publisher = new S3ClientLayer.S3MetricPublisher(metrics)
          _         = publisher.publish(collector.collect())
          snapshot <- metrics.snapshot
          tags      = Map("operation" -> "PutObject", "outcome" -> "success")
        yield assertTrue(
          snapshot.counters.exists { case (key, value) => key.name == MetricKeys.S3ApiCallsTotal && key.tags == tags && value == 1L },
          snapshot.counters.exists { case (key, value) => key.name == MetricKeys.S3RetriesTotal && key.tags == tags && value == 2L },
        )
      }
    )
