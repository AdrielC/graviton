package graviton.shared

import graviton.shared.schema.SchemaExplorer
import zio.json.*
import zio.schema.{DeriveSchema, Schema}

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

  /** Field definition inside a schema */
  final case class SchemaField(
    name: String,
    dataType: String,
    cardinality: String,
    nullable: Boolean,
    description: Option[String],
  ) derives JsonCodec

  /** Schema descriptor for an entity exposed by Graviton */
  final case class ObjectSchema(
    name: String,
    category: String,
    version: String,
    summary: Option[String],
    fields: List[SchemaField],
    sampleJson: Option[String],
  ) derives JsonCodec

  /** Executive summary item for the datalake dashboard. */
  final case class DatalakePillar(
    title: String,
    status: String,
    evidence: String,
    impact: String,
  ) derives JsonCodec

  /** Highlight section aggregating recent wins per area. */
  final case class DatalakeHighlight(
    category: String,
    bullets: List[String],
  ) derives JsonCodec

  /** Individual change stream entry (timeline row). */
  final case class DatalakeChangeEntry(
    date: String,
    area: String,
    update: String,
    impact: String,
    source: String,
  ) derives JsonCodec

  /** Health check or verification command relevant to the datalake. */
  final case class DatalakeHealthCheck(
    label: String,
    command: String,
    expectation: String,
  ) derives JsonCodec

  /** Operational note describing readiness or context. */
  final case class DatalakeOperationalNote(
    label: String,
    description: String,
  ) derives JsonCodec

  /** Source link for additional reading. */
  final case class DatalakeSourceLink(
    label: String,
    path: String,
  ) derives JsonCodec

  /** Aggregated view that powers the datalake change dashboard. */
  final case class DatalakeDashboard(
    lastUpdated: String,
    branch: String,
    pillars: List[DatalakePillar],
    highlights: List[DatalakeHighlight],
    changeStream: List[DatalakeChangeEntry],
    healthChecks: List[DatalakeHealthCheck],
    operationalConfidence: List[DatalakeOperationalNote],
    upcomingFocus: List[String],
    sources: List[DatalakeSourceLink],
  ) derives JsonCodec

  /** Serializable metaschema for the datalake dashboard payload. */
  final case class DatalakeMetaschema(
    format: String,
    astJson: String,
  ) derives JsonCodec

  /** Response envelope combining the snapshot data with its metaschema. */
  final case class DatalakeDashboardEnvelope(
    snapshot: DatalakeDashboard,
    metaschema: DatalakeMetaschema,
    schemaExplorer: SchemaExplorer.Graph,
  ) derives JsonCodec

  given Schema[DatalakeDashboard]        = DeriveSchema.gen[DatalakeDashboard]
  given Schema[DatalakePillar]           = DeriveSchema.gen[DatalakePillar]
  given Schema[DatalakeHighlight]        = DeriveSchema.gen[DatalakeHighlight]
  given Schema[DatalakeChangeEntry]      = DeriveSchema.gen[DatalakeChangeEntry]
  given Schema[DatalakeHealthCheck]      = DeriveSchema.gen[DatalakeHealthCheck]
  given Schema[DatalakeOperationalNote]  = DeriveSchema.gen[DatalakeOperationalNote]
  given Schema[DatalakeSourceLink]       = DeriveSchema.gen[DatalakeSourceLink]
}
