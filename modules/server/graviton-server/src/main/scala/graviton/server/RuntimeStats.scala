package graviton.server

import graviton.runtime.metrics.{MetricKeys, MetricsSnapshot}
import graviton.shared.ApiModels.*

/** Builds the public process-lifetime statistics response from metric counters. */
private[server] object RuntimeStats:

  def from(snapshot: MetricsSnapshot): SystemStats =
    def total(name: String): Long =
      snapshot.counters.iterator.collect { case (key, value) if key.name == name => value }.sum

    val fresh     = total(MetricKeys.FreshBlocksTotal)
    val duplicate = total(MetricKeys.DuplicateBlocksTotal)
    val blocks    = fresh + duplicate
    val ratio     = if blocks == 0L then 0.0 else duplicate.toDouble / blocks.toDouble

    SystemStats(
      totalBlobs = Count.applyUnsafe(total(MetricKeys.BlobIngestsTotal)),
      totalBytes = SizeBytes.applyUnsafe(total(MetricKeys.BytesIngestedTotal)),
      uniqueChunks = Count.applyUnsafe(fresh),
      deduplicationRatio = Ratio.applyUnsafe(ratio),
    )
