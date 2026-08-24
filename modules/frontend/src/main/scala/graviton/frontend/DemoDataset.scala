package graviton.frontend

import graviton.shared.ApiModels.*
import graviton.shared.cas.Sha256Cross
import graviton.shared.dashboard.DashboardSamples
import graviton.shared.schema.SchemaExplorer
import io.github.iltotore.iron.*

import java.nio.charset.StandardCharsets

/**
 * Static dataset used when the interactive demo is opened without a live Graviton service.
 * Provides representative metadata so the UI remains useful on GitHub Pages or other offline
 * contexts.
 */
final case class DemoDataset(
  health: HealthResponse,
  stats: SystemStats,
  blobs: Map[BlobId, DemoDataset.DemoBlob],
  schemas: List[ObjectSchema],
  datalakeDashboard: DatalakeDashboard,
  datalakeMetaschema: DatalakeMetaschema,
  datalakeSchemaExplorer: SchemaExplorer.Graph,
) {

  /** Ordered sample blob IDs to surface in the UI when running in demo mode. */
  val sampleBlobIds: List[BlobId] = blobs.keys.toList.sortBy(_.value)

  def metadataFor(id: BlobId): Option[BlobMetadata] = blobs.get(id).map(_.metadata)

  def manifestFor(id: BlobId): Option[BlobManifest] = blobs.get(id).map(_.manifest)

  def schemaCatalog: List[ObjectSchema] = schemas
}

object DemoDataset {

  /** Simple pair holding metadata + manifest for a demo blob. */
  final case class DemoBlob(metadata: BlobMetadata, manifest: BlobManifest)

  private def sz(v: Long): SizeBytes                                   = SizeBytes.applyUnsafe(v)
  private def cnt(v: Long): Count                                      = Count.applyUnsafe(v)
  private def ratio(v: Double): Ratio                                  = Ratio.applyUnsafe(v)
  private def chunk(offset: Long, size: Long, hash: String): ChunkInfo =
    ChunkInfo(offset = sz(offset), size = sz(size), hash = hash.refineUnsafe)

  private def demoBlob(blocks: List[String], createdAt: Long): DemoBlob =
    val blockBytes = blocks.map(_.getBytes(StandardCharsets.UTF_8))
    val offsets    = blockBytes.scanLeft(0L)((offset, bytes) => offset + bytes.length.toLong).dropRight(1)
    val bytes      = blockBytes.iterator.flatMap(_.iterator).toArray
    val digest     = Sha256Cross.hex(bytes).value
    val id         = BlobId.applyUnsafe(s"sha-256:$digest:${bytes.length}")
    val chunks     = offsets.zip(blockBytes).map { case (offset, value) =>
      chunk(offset, value.length.toLong, Sha256Cross.hex(value).value)
    }
    val metadata   = BlobMetadata(
      id = id,
      size = sz(bytes.length.toLong),
      contentType = Some("application/graviton-reference"),
      createdAt = createdAt,
      checksums = Map("sha-256" -> digest),
    )

    DemoBlob(metadata, BlobManifest(blobId = id, totalSize = metadata.size, chunks = chunks))

  /** Default dataset shipped with the documentation build. */
  val default: DemoDataset = {
    val createdAt = 1_728_192_000_000L // 2024-10-25T00:00:00Z

    val blobA = demoBlob(List("shared-header-v1", "graviton-intro!!", "archive-content1"), createdAt)
    val blobB = demoBlob(List("shared-header-v1", "pipeline-block-1", "storage-block--1"), createdAt + 86_400_000L)
    val blobC = demoBlob(List("case-file-head-1", "graviton-intro!!", "case-appendix--1"), createdAt + 2L * 86_400_000L)

    val blobAId         = blobA.metadata.id
    val blobBId         = blobB.metadata.id
    val blobABaseChunks = blobA.manifest.chunks
    val blobBChunks     = blobB.manifest.chunks
    val blobCChunks     = blobC.manifest.chunks
    val blobAChunk1     = blobABaseChunks(1)
    val blobBChunk0     = blobBChunks.head
    val blobBChunk1     = blobBChunks(1)
    val blobBChunk2     = blobBChunks(2)
    val allChunks       = blobABaseChunks ++ blobBChunks ++ blobCChunks
    val uniqueChunks    = allChunks.map(c => c.hash: String).distinct
    val blobs           = Map(blobAId -> blobA, blobBId -> blobB, blobC.metadata.id -> blobC)

    DemoDataset(
      health = HealthResponse(
        status = "Reference data",
        version = "reference-data-v1",
        uptime = 0L,
      ),
      stats = SystemStats(
        totalBlobs = cnt(blobs.size.toLong),
        totalBytes = sz(blobs.values.map(m => m.metadata.size: Long).sum),
        uniqueChunks = cnt(uniqueChunks.size.toLong),
        deduplicationRatio = ratio((allChunks.size - uniqueChunks.size).toDouble / allChunks.size.toDouble),
      ),
      blobs = blobs,
      schemas = List(
        ObjectSchema(
          name = "BlobMetadata",
          category = "core",
          version = "1.0.0",
          summary = Some("Primary descriptor for a stored blob."),
          fields = List(
            SchemaField("id", "BlobId", "1", nullable = false, description = Some("Unique content-addressed identifier.")),
            SchemaField("size", "Long", "1", nullable = false, description = Some("Total size of the blob in bytes.")),
            SchemaField("contentType", "String", "0..1", nullable = true, description = Some("Optional MIME type reported at ingest.")),
            SchemaField("createdAt", "EpochMillis", "1", nullable = false, description = Some("Creation timestamp in epoch milliseconds.")),
            SchemaField(
              "checksums",
              "Map[String,String]",
              "0..n",
              nullable = false,
              description = Some("Digest values keyed by algorithm."),
            ),
          ),
          sampleJson = Some(
            s"""{
               |  "id": "${blobAId.value}",
               |  "size": ${blobA.metadata.size.value},
               |  "contentType": "application/graviton-reference",
               |  "createdAt": 1728192000000,
               |  "checksums": {
               |    "sha-256": "${blobA.metadata.checksums("sha-256")}"
               |  }
               |}""".stripMargin
          ),
        ),
        ObjectSchema(
          name = "BlobManifest",
          category = "core",
          version = "1.0.0",
          summary = Some("Chunk-level view that powers streaming and deduplication."),
          fields = List(
            SchemaField("blobId", "BlobId", "1", nullable = false, description = Some("ID of the blob the manifest belongs to.")),
            SchemaField("totalSize", "Long", "1", nullable = false, description = Some("Total assembled size of the blob.")),
            SchemaField("chunks", "ChunkInfo", "1..n", nullable = false, description = Some("Ordered content-addressed blocks.")),
          ),
          sampleJson = Some(
            s"""{
               |  "blobId": "${blobBId.value}",
               |  "totalSize": ${blobB.metadata.size.value},
               |  "chunks": [
               |    { "offset": ${blobBChunk0.offset.value}, "size": ${blobBChunk0.size.value}, "hash": "${blobBChunk0.hash.toString}" },
               |    { "offset": ${blobBChunk1.offset.value}, "size": ${blobBChunk1.size.value}, "hash": "${blobBChunk1.hash.toString}" },
               |    { "offset": ${blobBChunk2.offset.value}, "size": ${blobBChunk2.size.value}, "hash": "${blobBChunk2.hash.toString}" }
               |  ]
               |}""".stripMargin
          ),
        ),
        ObjectSchema(
          name = "ChunkInfo",
          category = "streams",
          version = "1.0.0",
          summary = Some("Individual content-addressed blocks in an ordered manifest."),
          fields = List(
            SchemaField("offset", "Long", "1", nullable = false, description = Some("Byte offset from the start of the blob.")),
            SchemaField("size", "Long", "1", nullable = false, description = Some("Size of the chunk in bytes.")),
            SchemaField("hash", "String", "1", nullable = false, description = Some("Digest of the chunk payload.")),
          ),
          sampleJson = Some(
            s"""{ "offset": ${blobAChunk1.offset.value}, "size": ${blobAChunk1.size.value}, "hash": "${blobAChunk1.hash.toString}" }"""
          ),
        ),
      ),
      datalakeDashboard = DashboardSamples.snapshot,
      datalakeMetaschema = DashboardSamples.metaschema,
      datalakeSchemaExplorer = DashboardSamples.schemaExplorer,
    )
  }
}
