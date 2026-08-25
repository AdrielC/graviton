package graviton.shared

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import zio.json.*
import zio.schema.Schema

/**
 * Shared API models for the Graviton HTTP API.
 *
 * Each refined primitive owns an explicit codec built from zio-json's
 * primitive codec. Keeping that small bridge here avoids leaking
 * iron-zio-json's zio-json version into every external consumer.
 */
object ApiModels {

  /** Blob identifier: non-empty, max 256 chars. `BlobId <: String`. */
  type BlobId = BlobId.T
  object BlobId extends RefinedSubtype[String, MinLength[1] & MaxLength[256]]:
    given Schema[BlobId]    =
      Schema[String].transformOrFail(
        s => either(s),
        id => Right(id.value),
      )
    given JsonCodec[BlobId] =
      summon[JsonCodec[String]].transformOrFail(either, _.value)

  /** Non-negative size in bytes. `SizeBytes <: Long`. */
  type SizeBytes = SizeBytes.T
  object SizeBytes extends RefinedSubtype[Long, GreaterEqual[0L] & LessEqual[1099511627776L]]:
    given JsonCodec[SizeBytes] =
      summon[JsonCodec[Long]].transformOrFail(either, _.value)

  /** Non-negative count. `Count <: Long`. */
  type Count = Count.T
  object Count extends RefinedSubtype[Long, GreaterEqual[0L]]:
    given JsonCodec[Count] =
      summon[JsonCodec[Long]].transformOrFail(either, _.value)

  /** Non-negative ratio ∈ [0.0, ∞). `Ratio <: Double`. */
  type Ratio = Ratio.T
  object Ratio extends RefinedSubtype[Double, GreaterEqual[0.0]]:
    given JsonCodec[Ratio] =
      summon[JsonCodec[Double]].transformOrFail(either, _.value)

  /** System stats */
  final case class SystemStats(
    totalBlobs: Count,
    totalBytes: SizeBytes,
    uniqueChunks: Count,
    deduplicationRatio: Ratio,
  ) derives JsonCodec

  /** Health check response */
  final case class HealthResponse(
    status: String,
    version: String,
    uptime: Long,
  ) derives JsonCodec

  /** One blob currently persisted by the configured manifest repository. */
  final case class BlobSummary(
    id: BlobId,
    size: SizeBytes,
    createdAt: Long,
    digest: String,
    blockCount: Count,
  ) derives JsonCodec

  /** One real block reference from a persisted manifest. */
  final case class BlobBlock(
    index: Count,
    contentId: String,
    offset: SizeBytes,
    size: SizeBytes,
  ) derives JsonCodec

  /** Persisted blob metadata and block layout. */
  final case class BlobDetails(
    summary: BlobSummary,
    blocks: List[BlobBlock],
  ) derives JsonCodec

  /** Current durable blob inventory. */
  final case class BlobListResponse(
    blobs: List[BlobSummary],
    nextCursor: Option[String] = None,
  ) derives JsonCodec

  /** Result returned after the server has fully persisted an upload. */
  final case class BlobUploadResult(
    blob: BlobSummary,
    freshBlocks: Count,
    duplicateBlocks: Count,
    durationSeconds: Double,
  ) derives JsonCodec

  /** Result of reading and hashing a persisted blob on the server. */
  final case class BlobVerificationResult(
    id: BlobId,
    verified: Boolean,
    bytesChecked: SizeBytes,
  ) derives JsonCodec
}
