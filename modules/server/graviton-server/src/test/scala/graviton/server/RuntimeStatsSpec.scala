package graviton.server

import graviton.runtime.metrics.{MetricKey, MetricKeys, MetricsSnapshot}
import zio.test.*

object RuntimeStatsSpec extends ZIOSpecDefault:

  override def spec =
    suite("RuntimeStats")(
      test("aggregates counters across tags and computes byte-weighted reuse") {
        val firstTags  = Map("backend" -> "cas", "chunker" -> "fixed-1024")
        val secondTags = Map("backend" -> "cas", "chunker" -> "fastcdc")
        val snapshot   = MetricsSnapshot(
          counters = Map(
            MetricKey(MetricKeys.BlobIngestsTotal, firstTags)          -> 2L,
            MetricKey(MetricKeys.BlobIngestsTotal, secondTags)         -> 1L,
            MetricKey(MetricKeys.BytesIngestedTotal, firstTags)        -> 120L,
            MetricKey(MetricKeys.BytesIngestedTotal, secondTags)       -> 80L,
            MetricKey(MetricKeys.FreshBlocksTotal, firstTags)          -> 6L,
            MetricKey(MetricKeys.DuplicateBlocksTotal, firstTags)      -> 2L,
            MetricKey(MetricKeys.FreshBlocksTotal, secondTags)         -> 1L,
            MetricKey(MetricKeys.DuplicateBlocksTotal, secondTags)     -> 1L,
            MetricKey(MetricKeys.FreshBlockBytesTotal, firstTags)      -> 90L,
            MetricKey(MetricKeys.DuplicateBlockBytesTotal, firstTags)  -> 10L,
            MetricKey(MetricKeys.FreshBlockBytesTotal, secondTags)     -> 50L,
            MetricKey(MetricKeys.DuplicateBlockBytesTotal, secondTags) -> 50L,
          ),
          gauges = Map.empty,
        )

        val stats = RuntimeStats.from(snapshot)

        assertTrue(
          stats.totalBlobs.value == 3L,
          stats.totalBytes.value == 200L,
          stats.uniqueChunks.value == 7L,
          math.abs(stats.deduplicationRatio.value - 0.3) < 0.000001,
        )
      },
      test("empty counters return zeros") {
        val stats = RuntimeStats.from(MetricsSnapshot.empty)

        assertTrue(
          stats.totalBlobs.value == 0L,
          stats.totalBytes.value == 0L,
          stats.uniqueChunks.value == 0L,
          stats.deduplicationRatio.value == 0.0,
        )
      },
    )
