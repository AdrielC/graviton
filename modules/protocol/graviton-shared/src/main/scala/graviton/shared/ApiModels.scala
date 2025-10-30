package graviton.shared

import zio.json.*

/** Shared API models for Graviton HTTP API */
object ApiModels {

  /** Binary blob identifier */
  final case class BlobId(value: String) derives JsonCodec

  /** Blob metadata */
  final case class BlobMetadata(
    id: BlobId,
    size: Long,
    contentType: Option[String],
    createdAt: Long,
    checksums: Map[String, String],
  ) derives JsonCodec

  /** Blob upload request */
  final case class UploadRequest(
    contentType: Option[String],
    expectedSize: Option[Long],
  ) derives JsonCodec

  /** Upload response with blob ID */
  final case class UploadResponse(
    blobId: BlobId,
    uploadUrl: String,
  ) derives JsonCodec

  /** Blob retrieval request */
  final case class GetBlobRequest(
    blobId: BlobId
  ) derives JsonCodec

  /** Chunk information for streaming */
  final case class ChunkInfo(
    offset: Long,
    size: Long,
    hash: String,
  ) derives JsonCodec

  /** Manifest for a blob */
  final case class BlobManifest(
    blobId: BlobId,
    totalSize: Long,
    chunks: List[ChunkInfo],
  ) derives JsonCodec

  /** System stats */
  final case class SystemStats(
    totalBlobs: Long,
    totalBytes: Long,
    uniqueChunks: Long,
    deduplicationRatio: Double,
  ) derives JsonCodec

  /** Health check response */
  final case class HealthResponse(
    status: String,
    version: String,
    uptime: Long,
  ) derives JsonCodec
}
