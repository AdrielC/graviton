package graviton.frontend

import graviton.shared.ApiModels.*
import graviton.shared.dashboard.DashboardSamples

/**
 * Static dataset used when the interactive demo is opened without a live Graviton service.
 * Provides representative metadata so the UI remains useful on GitHub Pages or other offline
 * contexts.
 */
final case class DemoData(
  health: HealthResponse,
  stats: SystemStats,
  blobs: Map[BlobId, DemoData.DemoBlob],
  schemas: List[ObjectSchema],
  simulatedUpload: UploadResponse,
  datalakeDashboard: DatalakeDashboard,
  datalakeMetaschema: DatalakeMetaschema,
) {

  /** Ordered sample blob IDs to surface in the UI when running in demo mode. */
  val sampleBlobIds: List[BlobId] = blobs.keys.toList.sortBy(_.value)

  def metadataFor(id: BlobId): Option[BlobMetadata] = blobs.get(id).map(_.metadata)

  def manifestFor(id: BlobId): Option[BlobManifest] = blobs.get(id).map(_.manifest)

  def schemaCatalog: List[ObjectSchema] = schemas

  def simulateUpload(request: UploadRequest): UploadResponse = simulatedUpload.copy(
    blobId = BlobId(s"sha256:demo-upload-${request.expectedSize.getOrElse(0L)}")
  )
}

object DemoData {

  /** Simple pair holding metadata + manifest for a demo blob. */
  final case class DemoBlob(metadata: BlobMetadata, manifest: BlobManifest)

  /** Default dataset shipped with the documentation build. */
  val default: DemoData = {
    val createdAt = 1_728_192_000_000L // 2024-10-25T00:00:00Z

    val blobAId = BlobId("sha256:demo-welcome")
    val blobBId = BlobId("sha256:demo-manifest")
    val blobCId = BlobId("sha256:demo-shared")

    val blobABaseChunks = List(
      ChunkInfo(offset = 0L, size = 512L, hash = "chunk:01:welcome"),
      ChunkInfo(offset = 512L, size = 768L, hash = "chunk:02:intro"),
      ChunkInfo(offset = 1_280L, size = 640L, hash = "chunk:03:overview"),
    )

    val blobBChunks = List(
      ChunkInfo(offset = 0L, size = 512L, hash = "chunk:01:welcome"),
      ChunkInfo(offset = 512L, size = 896L, hash = "chunk:04:pipeline"),
      ChunkInfo(offset = 1_408L, size = 960L, hash = "chunk:05:storage"),
    )

    val blobCChunks = List(
      ChunkInfo(offset = 0L, size = 256L, hash = "chunk:06:header"),
      ChunkInfo(offset = 256L, size = 768L, hash = "chunk:02:intro"),
      ChunkInfo(offset = 1_024L, size = 1_024L, hash = "chunk:07:appendix"),
    )

    val blobAMetadata = BlobMetadata(
      id = blobAId,
      size = blobABaseChunks.map(_.size).sum,
      contentType = Some("application/graviton-demo"),
      createdAt = createdAt,
      checksums = Map(
        "sha256" -> "demo0a7f7bc2b3a6c410502ac48f3564c0f54f58c1d9f9b8498897c1d1f3af001",
        "blake3" -> "13f7a8b10cd61b9f8f0e11aed0056fb7",
      ),
    )

    val blobBMetadata = BlobMetadata(
      id = blobBId,
      size = blobBChunks.map(_.size).sum,
      contentType = Some("application/graviton-demo"),
      createdAt = createdAt + 86_400_000L,
      checksums = Map(
        "sha256" -> "demo8e3b92c0b4f01f3d7f4a7e6f0127da3c89b3771c9278ce31cfa952fa9b111",
        "blake3" -> "4b8c24157f2a27c3c2c41ab2f0be10cd",
      ),
    )

    val blobCMetadata = BlobMetadata(
      id = blobCId,
      size = blobCChunks.map(_.size).sum,
      contentType = Some("application/graviton-demo"),
      createdAt = createdAt + 2L * 86_400_000L,
      checksums = Map(
        "sha256" -> "demo2dd8b4d1c2f41c5f7b6a2fbe8d02c3417c0cf15e9e8dd4ce70b3fcb1c7dd77",
        "blake3" -> "5d61fa893c0d1a2b4c3e5f60798ba21f",
      ),
    )

    val blobs = Map(
      blobAId -> DemoBlob(
        metadata = blobAMetadata,
        manifest = BlobManifest(blobId = blobAId, totalSize = blobAMetadata.size, chunks = blobABaseChunks),
      ),
      blobBId -> DemoBlob(
        metadata = blobBMetadata,
        manifest = BlobManifest(blobId = blobBId, totalSize = blobBMetadata.size, chunks = blobBChunks),
      ),
      blobCId -> DemoBlob(
        metadata = blobCMetadata,
        manifest = BlobManifest(blobId = blobCId, totalSize = blobCMetadata.size, chunks = blobCChunks),
      ),
    )

    DemoData(
      health = HealthResponse(
        status = "Demo",
        version = "0.1.0-demo",
        uptime = 42L * 60L * 60L * 1000L,
      ),
      stats = SystemStats(
        totalBlobs = blobs.size,
        totalBytes = blobs.values.map(_.metadata.size).sum,
        uniqueChunks = (blobABaseChunks ++ blobBChunks ++ blobCChunks).map(_.hash).distinct.size,
        deduplicationRatio = 1.0 / 0.72, // illustrative value ~1.39:1
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
            """{
              |  "id": "sha256:demo-welcome",
              |  "size": 1920,
              |  "contentType": "application/graviton-demo",
              |  "createdAt": 1728192000000,
              |  "checksums": {
              |    "sha256": "demo0a7f7bc2b3a6c410502ac48f3564c0f54f58c1d9f9b8498897c1d1f3af001"
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
            SchemaField("chunks", "ChunkInfo", "1..n", nullable = false, description = Some("Ordered content-defined chunks.")),
          ),
          sampleJson = Some(
            """{
              |  "blobId": "sha256:demo-manifest",
              |  "totalSize": 2368,
              |  "chunks": [
              |    { "offset": 0, "size": 512, "hash": "chunk:01:welcome" },
              |    { "offset": 512, "size": 896, "hash": "chunk:04:pipeline" },
              |    { "offset": 1408, "size": 960, "hash": "chunk:05:storage" }
              |  ]
              |}""".stripMargin
          ),
        ),
        ObjectSchema(
          name = "ChunkInfo",
          category = "streams",
          version = "1.0.0",
          summary = Some("Individual content-defined blocks produced by FastCDC."),
          fields = List(
            SchemaField("offset", "Long", "1", nullable = false, description = Some("Byte offset from the start of the blob.")),
            SchemaField("size", "Long", "1", nullable = false, description = Some("Size of the chunk in bytes.")),
            SchemaField("hash", "String", "1", nullable = false, description = Some("Digest of the chunk payload.")),
          ),
          sampleJson = Some(
            """{ "offset": 512, "size": 896, "hash": "chunk:04:pipeline" }"""
          ),
        ),
      ),
      simulatedUpload = UploadResponse(
        blobId = BlobId("sha256:demo-upload"),
        uploadUrl = "https://demo.invalid/upload",
      ),
      datalakeDashboard = DashboardSamples.snapshot,
      datalakeMetaschema = DashboardSamples.metaschema,
    )
  }
}
