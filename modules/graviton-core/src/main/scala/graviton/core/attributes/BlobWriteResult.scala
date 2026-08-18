package graviton.core.attributes

import graviton.core.keys.BinaryKey
import graviton.core.locator.BlobLocator

/**
 * The result of writing a blob to a store.
 *
 * Contains the content-addressed key, the backend locator, the
 * advertised/confirmed attributes collected during ingest, and
 * optional ingest statistics (block counts, dedup info, duration).
 */
final case class BlobWriteResult(
  key: BinaryKey,
  locator: BlobLocator,
  attributes: BinaryAttributes,
  stats: IngestStats = IngestStats.empty,
)

/**
 * Statistics collected during a blob ingest pipeline run.
 *
 * These are populated by `CasBlobStore.put()` from the `BlockBatchResult`.
 */
final case class IngestStats(
  totalBytes: Long,
  blockCount: Int,
  freshBlocks: Int,
  duplicateBlocks: Int,
  durationSeconds: Double,
):
  /** Fraction of blocks that were already present in the store (0.0 to 1.0). */
  def dedupRatio: Double =
    if blockCount == 0 then 0.0
    else duplicateBlocks.toDouble / blockCount.toDouble

object IngestStats:
  val empty: IngestStats = IngestStats(0L, 0, 0, 0, 0.0)
