package graviton.shared

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.zioJson.given
import zio.json.*
import zio.schema.Schema

/**
 * Shared API models for the Graviton HTTP API.
 *
 * Codec note: under Scala 3.8+, `derives JsonCodec` on case classes that
 * carry Iron `RefinedSubtype` members fails to locate a combined
 * `JsonCodec[T]` — iron-zio-json only publishes separate
 * `JsonEncoder[T]` / `JsonDecoder[T]` givens, and the compiler's
 * path-dependent type normalisation no longer bridges them
 * automatically. Each RefinedSubtype below exposes an explicit
 * `given JsonCodec[T]` composed from those two givens so the derivation
 * on the case classes keeps working unchanged.
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
      JsonCodec(summon[JsonEncoder[BlobId]], summon[JsonDecoder[BlobId]])

  /** Non-negative size in bytes. `SizeBytes <: Long`. */
  type SizeBytes = SizeBytes.T
  object SizeBytes extends RefinedSubtype[Long, GreaterEqual[0L]]:
    given JsonCodec[SizeBytes] =
      JsonCodec(summon[JsonEncoder[SizeBytes]], summon[JsonDecoder[SizeBytes]])

  /** Non-negative count. `Count <: Long`. */
  type Count = Count.T
  object Count extends RefinedSubtype[Long, GreaterEqual[0L]]:
    given JsonCodec[Count] =
      JsonCodec(summon[JsonEncoder[Count]], summon[JsonDecoder[Count]])

  /** Non-negative ratio ∈ [0.0, ∞). `Ratio <: Double`. */
  type Ratio = Ratio.T
  object Ratio extends RefinedSubtype[Double, GreaterEqual[0.0]]:
    given JsonCodec[Ratio] =
      JsonCodec(summon[JsonEncoder[Ratio]], summon[JsonDecoder[Ratio]])

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
    blobs: List[BlobSummary]
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
