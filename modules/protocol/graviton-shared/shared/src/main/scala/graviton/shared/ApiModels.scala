package graviton.shared

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import zio.blocks.schema.Schema as BlocksSchema
import zio.json.*
import zio.schema.Schema

/**
 * Shared API models for the Graviton HTTP API.
 *
 * A ZIO Blocks schema-derived JSON wire shape is the canonical cross-platform
 * boundary used by the server, JVM SDK, and Scala.js client. Runtime mapping
 * validates every refined public value. The zio-json codecs remain as a
 * compatibility surface for existing Graviton consumers.
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

  /** Canonical UUID identifying one resumable upload session. */
  type UploadId = UploadId.T
  object UploadId extends RefinedSubtype[String, Match["[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"]]:
    given JsonCodec[UploadId] = summon[JsonCodec[String]].transformOrFail(either, _.value)

  /** Durable byte offset, including zero and the completed 1 TiB boundary. */
  type UploadOffsetBytes = UploadOffsetBytes.T
  object UploadOffsetBytes extends RefinedSubtype[Long, GreaterEqual[0L] & LessEqual[1099511627776L]]:
    given JsonCodec[UploadOffsetBytes] = summon[JsonCodec[Long]].transformOrFail(either, _.value)

  enum UploadState derives JsonCodec:
    case Open
    case Committing
    case Committed
    case Cancelled

  /** Plain wire records avoid the opaque numeric wrapper defect in 0.0.51. */
  private object Wire:
    final case class SystemStats(
      totalBlobs: Long,
      totalBytes: Long,
      uniqueChunks: Long,
      deduplicationRatio: Double,
    )
    object SystemStats:
      given BlocksSchema[SystemStats] = BlocksSchema.derived

    final case class BlobSummary(
      id: String,
      size: Long,
      createdAt: Long,
      digest: String,
      blockCount: Long,
    )
    object BlobSummary:
      given BlocksSchema[BlobSummary] = BlocksSchema.derived

    final case class BlobBlock(
      index: Long,
      contentId: String,
      offset: Long,
      size: Long,
    )
    object BlobBlock:
      given BlocksSchema[BlobBlock] = BlocksSchema.derived

    final case class BlobMetadata(
      schemaVersion: Int,
      codecVersion: Int,
      mediaType: String,
      chunker: String,
    )
    object BlobMetadata:
      given BlocksSchema[BlobMetadata] = BlocksSchema.derived

    final case class BlobDetails(
      summary: BlobSummary,
      blocks: List[BlobBlock],
      metadata: Option[BlobMetadata],
      nextCursor: Option[String],
    ):
      def this(summary: BlobSummary, blocks: List[BlobBlock]) = this(summary, blocks, None, None)

      def copy(summary: BlobSummary, blocks: List[BlobBlock]): BlobDetails =
        BlobDetails(summary, blocks, metadata, nextCursor)

    object BlobDetails:
      def apply(summary: BlobSummary, blocks: List[BlobBlock]): BlobDetails =
        new BlobDetails(summary, blocks, None, None)

      given BlocksSchema[BlobDetails] = BlocksSchema.derived

    final case class BlobListResponse(blobs: List[BlobSummary], nextCursor: Option[String])
    object BlobListResponse:
      given BlocksSchema[BlobListResponse] = BlocksSchema.derived

    final case class BlobUploadResult(
      blob: BlobSummary,
      freshBlocks: Long,
      duplicateBlocks: Long,
      durationSeconds: Double,
    )
    object BlobUploadResult:
      given BlocksSchema[BlobUploadResult] = BlocksSchema.derived

    final case class BlobVerificationResult(id: String, verified: Boolean, bytesChecked: Long)
    object BlobVerificationResult:
      given BlocksSchema[BlobVerificationResult] = BlocksSchema.derived

    final case class ResumableUploadStatus(
      id: String,
      offset: Long,
      expectedSize: Option[Long],
      expiresAt: Long,
      state: String,
      committedBlob: Option[String],
    )
    object ResumableUploadStatus:
      given BlocksSchema[ResumableUploadStatus] = BlocksSchema.derived

  private def validated[A](field: String, value: Either[String, A]): Either[String, A] =
    value.left.map(message => s"$field: $message")

  private def traverse[A, B](values: List[A])(f: A => Either[String, B]): Either[String, List[B]] =
    @scala.annotation.tailrec
    def loop(remaining: List[A], reversed: List[B]): Either[String, List[B]] =
      remaining match
        case head :: tail =>
          f(head) match
            case Left(error)  => Left(error)
            case Right(value) => loop(tail, value :: reversed)
        case Nil          => Right(reversed.reverse)

    loop(values, Nil)

  private def summaryToWire(value: BlobSummary): Wire.BlobSummary =
    Wire.BlobSummary(
      value.id.value,
      value.size.value,
      value.createdAt,
      value.digest,
      value.blockCount.value,
    )

  private def summaryFromWire(value: Wire.BlobSummary): Either[String, BlobSummary] =
    for
      id         <- validated("id", BlobId.either(value.id))
      size       <- validated("size", SizeBytes.either(value.size))
      blockCount <- validated("blockCount", Count.either(value.blockCount))
    yield BlobSummary(id, size, value.createdAt, value.digest, blockCount)

  private def blockToWire(value: BlobBlock): Wire.BlobBlock =
    Wire.BlobBlock(value.index.value, value.contentId, value.offset.value, value.size.value)

  private def blockFromWire(value: Wire.BlobBlock): Either[String, BlobBlock] =
    for
      index  <- validated("index", Count.either(value.index))
      offset <- validated("offset", SizeBytes.either(value.offset))
      size   <- validated("size", SizeBytes.either(value.size))
    yield BlobBlock(index, value.contentId, offset, size)

  /** Stable JSON error envelope returned by the HTTP API. */
  final case class ApiError(
    error: String,
    message: String,
  ) derives JsonCodec
  object ApiError:
    private given BlocksSchema[ApiError] = BlocksSchema.derived
    given ApiJsonCodec[ApiError]         = ApiJsonCodec.derived

  /** System stats */
  final case class SystemStats(
    totalBlobs: Count,
    totalBytes: SizeBytes,
    uniqueChunks: Count,
    deduplicationRatio: Ratio,
  ) derives JsonCodec
  object SystemStats:
    given ApiJsonCodec[SystemStats] =
      ApiJsonCodec.mapped[SystemStats, Wire.SystemStats](value =>
        Wire.SystemStats(
          value.totalBlobs.value,
          value.totalBytes.value,
          value.uniqueChunks.value,
          value.deduplicationRatio.value,
        )
      )(value =>
        for
          totalBlobs         <- validated("totalBlobs", Count.either(value.totalBlobs))
          totalBytes         <- validated("totalBytes", SizeBytes.either(value.totalBytes))
          uniqueChunks       <- validated("uniqueChunks", Count.either(value.uniqueChunks))
          deduplicationRatio <- validated("deduplicationRatio", Ratio.either(value.deduplicationRatio))
        yield SystemStats(totalBlobs, totalBytes, uniqueChunks, deduplicationRatio)
      )

  /** Health check response */
  final case class HealthResponse(
    status: String,
    version: String,
    uptime: Long,
  ) derives JsonCodec
  object HealthResponse:
    private given BlocksSchema[HealthResponse] = BlocksSchema.derived
    given ApiJsonCodec[HealthResponse]         = ApiJsonCodec.derived

  /** One blob currently persisted by the configured manifest repository. */
  final case class BlobSummary(
    id: BlobId,
    size: SizeBytes,
    createdAt: Long,
    digest: String,
    blockCount: Count,
  ) derives JsonCodec
  object BlobSummary:
    given ApiJsonCodec[BlobSummary] =
      ApiJsonCodec.mapped[BlobSummary, Wire.BlobSummary](summaryToWire)(summaryFromWire)

  /** One real block reference from a persisted manifest. */
  final case class BlobBlock(
    index: Count,
    contentId: String,
    offset: SizeBytes,
    size: SizeBytes,
  ) derives JsonCodec
  object BlobBlock:
    given ApiJsonCodec[BlobBlock] =
      ApiJsonCodec.mapped[BlobBlock, Wire.BlobBlock](blockToWire)(blockFromWire)

  /** Persisted blob metadata and block layout. */
  final case class BlobMetadata(
    schemaVersion: Int,
    codecVersion: Int,
    mediaType: String,
    chunker: String,
  ) derives JsonCodec
  object BlobMetadata:
    private given BlocksSchema[BlobMetadata] = BlocksSchema.derived
    given ApiJsonCodec[BlobMetadata]         = ApiJsonCodec.derived

  /** One bounded page from a persisted manifest. */
  final case class BlobDetails(
    summary: BlobSummary,
    blocks: List[BlobBlock],
    metadata: Option[BlobMetadata] = None,
    nextCursor: Option[String] = None,
  ) derives JsonCodec:
    def this(summary: BlobSummary, blocks: List[BlobBlock]) = this(summary, blocks, None, None)

    def copy(summary: BlobSummary, blocks: List[BlobBlock]): BlobDetails =
      BlobDetails(summary, blocks, metadata, nextCursor)

  object BlobDetails:
    def apply(summary: BlobSummary, blocks: List[BlobBlock]): BlobDetails =
      new BlobDetails(summary, blocks, None, None)

    given ApiJsonCodec[BlobDetails] =
      ApiJsonCodec.mapped[BlobDetails, Wire.BlobDetails](value =>
        Wire.BlobDetails(
          summaryToWire(value.summary),
          value.blocks.map(blockToWire),
          value.metadata.map(meta => Wire.BlobMetadata(meta.schemaVersion, meta.codecVersion, meta.mediaType, meta.chunker)),
          value.nextCursor,
        )
      )(value =>
        for
          summary <- summaryFromWire(value.summary)
          blocks  <- traverse(value.blocks)(blockFromWire)
        yield BlobDetails(
          summary,
          blocks,
          value.metadata.map(meta => BlobMetadata(meta.schemaVersion, meta.codecVersion, meta.mediaType, meta.chunker)),
          value.nextCursor,
        )
      )

  /** Current durable blob inventory. */
  final case class BlobListResponse(
    blobs: List[BlobSummary],
    nextCursor: Option[String] = None,
  ) derives JsonCodec
  object BlobListResponse:
    given ApiJsonCodec[BlobListResponse] =
      ApiJsonCodec.mapped[BlobListResponse, Wire.BlobListResponse](value =>
        Wire.BlobListResponse(value.blobs.map(summaryToWire), value.nextCursor)
      )(value => traverse(value.blobs)(summaryFromWire).map(BlobListResponse(_, value.nextCursor)))

  /** Result returned after the server has fully persisted an upload. */
  final case class BlobUploadResult(
    blob: BlobSummary,
    freshBlocks: Count,
    duplicateBlocks: Count,
    durationSeconds: Double,
  ) derives JsonCodec
  object BlobUploadResult:
    given ApiJsonCodec[BlobUploadResult] =
      ApiJsonCodec.mapped[BlobUploadResult, Wire.BlobUploadResult](value =>
        Wire.BlobUploadResult(
          summaryToWire(value.blob),
          value.freshBlocks.value,
          value.duplicateBlocks.value,
          value.durationSeconds,
        )
      )(value =>
        for
          blob            <- summaryFromWire(value.blob)
          freshBlocks     <- validated("freshBlocks", Count.either(value.freshBlocks))
          duplicateBlocks <- validated("duplicateBlocks", Count.either(value.duplicateBlocks))
        yield BlobUploadResult(blob, freshBlocks, duplicateBlocks, value.durationSeconds)
      )

  /** Result of reading and hashing a persisted blob on the server. */
  final case class BlobVerificationResult(
    id: BlobId,
    verified: Boolean,
    bytesChecked: SizeBytes,
  ) derives JsonCodec
  object BlobVerificationResult:
    given ApiJsonCodec[BlobVerificationResult] =
      ApiJsonCodec.mapped[BlobVerificationResult, Wire.BlobVerificationResult](value =>
        Wire.BlobVerificationResult(value.id.value, value.verified, value.bytesChecked.value)
      )(value =>
        for
          id           <- validated("id", BlobId.either(value.id))
          bytesChecked <- validated("bytesChecked", SizeBytes.either(value.bytesChecked))
        yield BlobVerificationResult(id, value.verified, bytesChecked)
      )

  /** Durable checkpoint returned by create, append, status, and commit calls. */
  final case class ResumableUploadStatus(
    id: UploadId,
    offset: UploadOffsetBytes,
    expectedSize: Option[SizeBytes],
    expiresAt: Long,
    state: UploadState,
    committedBlob: Option[BlobId],
  ) derives JsonCodec

  object ResumableUploadStatus:
    given ApiJsonCodec[ResumableUploadStatus] =
      ApiJsonCodec.mapped[ResumableUploadStatus, Wire.ResumableUploadStatus](value =>
        Wire.ResumableUploadStatus(
          value.id.value,
          value.offset.value,
          value.expectedSize.map(_.value),
          value.expiresAt,
          value.state.toString,
          value.committedBlob.map(_.value),
        )
      )(value =>
        for
          id       <- validated("id", UploadId.either(value.id))
          offset   <- validated("offset", UploadOffsetBytes.either(value.offset))
          expected <- value.expectedSize match
                        case None        => Right(None)
                        case Some(bytes) => validated("expectedSize", SizeBytes.either(bytes)).map(Some(_))
          state    <- UploadState.values.find(_.toString == value.state).toRight(s"state: invalid value '${value.state}'")
          blob     <- value.committedBlob match
                        case None       => Right(None)
                        case Some(blob) => validated("committedBlob", BlobId.either(blob)).map(Some(_))
        yield ResumableUploadStatus(id, offset, expected, value.expiresAt, state, blob)
      )
}
