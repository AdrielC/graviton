package graviton.shared.pipeline

import zio.json.*

/**
 * Shared, cross-compiled model of the Transducer pipeline.
 *
 * This module mirrors the JVM-only `graviton.core.scan.Transducer` algebra as
 * serializable descriptors so the Scala.js frontend can visualize real pipeline
 * stages without duplicating domain knowledge.
 *
 * The JVM side populates these descriptors from the actual `IngestPipeline` /
 * `TransducerKit` definitions; the JS side renders them.
 */

/** A single named field in a transducer summary Record. */
final case class SummaryField(
  name: String,
  scalaType: String,
  description: String,
) derives JsonCodec

/** Describes one stage in a transducer pipeline. */
final case class TransducerStage(
  id: String,
  name: String,
  inputType: String,
  outputType: String,
  summaryFields: List[SummaryField],
  description: String,
  hotStateDescription: String,
) derives JsonCodec

/** How two stages are composed. */
enum CompositionOp derives JsonCodec:
  case Sequential // >>>
  case Fanout     // &&&

/** A composed pipeline: a non-empty list of stages with composition operators between them. */
final case class PipelineDescriptor(
  name: String,
  description: String,
  stages: List[TransducerStage],
  operators: List[CompositionOp],
  scalaExpression: String,
) derives JsonCodec:
  /** All summary fields from all stages, in order. */
  def allSummaryFields: List[SummaryField] =
    stages.flatMap(_.summaryFields)

/** Pre-built pipeline descriptors corresponding to real Graviton transducers. */
object PipelineCatalog:

  val countBytes: TransducerStage = TransducerStage(
    id = "countBytes",
    name = "Count Bytes",
    inputType = "Chunk[Byte]",
    outputType = "Chunk[Byte]",
    summaryFields = List(
      SummaryField("totalBytes", "Long", "Running total of bytes processed"),
    ),
    description = "Pass-through counter. Tracks total bytes without modifying the stream.",
    hotStateDescription = "Hot = Long (zero-alloc)",
  )

  val hashBytes: TransducerStage = TransducerStage(
    id = "hashBytes",
    name = "Hash Bytes",
    inputType = "Chunk[Byte]",
    outputType = "Chunk[Byte]",
    summaryFields = List(
      SummaryField("digestHex", "String", "Hex-encoded digest of the entire stream"),
      SummaryField("hashBytes", "Long", "Total bytes fed to the hasher"),
    ),
    description = "Incremental BLAKE3/SHA-256 hash. Pass-through — bytes flow unchanged while the hasher accumulates.",
    hotStateDescription = "Hot = (Either[String, Hasher], Long)",
  )

  val rechunk: TransducerStage = TransducerStage(
    id = "rechunk",
    name = "Rechunk",
    inputType = "Chunk[Byte]",
    outputType = "Chunk[Byte]",
    summaryFields = List(
      SummaryField("blockCount", "Long", "Number of complete blocks emitted"),
      SummaryField("rechunkFill", "Int", "Bytes buffered in the current incomplete block"),
    ),
    description = "Fixed-size rechunker. Accumulates bytes into blocks of exactly blockSize, flushing the remainder at end-of-stream.",
    hotStateDescription = "Hot = (Array[Byte], Int, Long) — buf, fill, count",
  )

  val blockKeyDeriver: TransducerStage = TransducerStage(
    id = "blockKey",
    name = "Block Key Deriver",
    inputType = "Chunk[Byte]",
    outputType = "CanonicalBlock",
    summaryFields = List(
      SummaryField("blocksKeyed", "Long", "Number of blocks that received a content-addressed key"),
    ),
    description = "Per-block hashing to derive BinaryKey.Block via KeyBits.create. Bridges byte processing to CAS semantics.",
    hotStateDescription = "Hot = Long",
  )

  val dedup: TransducerStage = TransducerStage(
    id = "dedup",
    name = "Deduplication",
    inputType = "CanonicalBlock",
    outputType = "CanonicalBlock",
    summaryFields = List(
      SummaryField("uniqueCount", "Long", "Fresh (never-seen) blocks passed through"),
      SummaryField("duplicateCount", "Long", "Duplicate blocks dropped"),
    ),
    description = "In-memory or bloom-filter dedup check. Fresh blocks pass through; duplicates are dropped.",
    hotStateDescription = "Hot = (Set[K], Long, Long)",
  )

  val blockCounter: TransducerStage = TransducerStage(
    id = "blockCounter",
    name = "Block Counter",
    inputType = "Chunk[Byte]",
    outputType = "Chunk[Byte]",
    summaryFields = List(
      SummaryField("blockCount", "Long", "Number of blocks counted"),
    ),
    description = "Pass-through block counter.",
    hotStateDescription = "Hot = Long",
  )

  val compress: TransducerStage = TransducerStage(
    id = "compress",
    name = "Compress",
    inputType = "Chunk[Byte]",
    outputType = "Chunk[Byte]",
    summaryFields = List(
      SummaryField("compressedBytes", "Long", "Total bytes after compression"),
      SummaryField("ratio", "Double", "Compression ratio (original / compressed)"),
    ),
    description = "Zstd compression per block. Tracks compression ratio in summary.",
    hotStateDescription = "Hot = (Long, Long) — compressedTotal, originalTotal",
  )

  val bombGuard: TransducerStage = TransducerStage(
    id = "bombGuard",
    name = "Bomb Guard",
    inputType = "Chunk[Byte]",
    outputType = "Chunk[Byte]",
    summaryFields = List(
      SummaryField("totalSeen", "Long", "Total bytes seen before guard"),
      SummaryField("rejected", "Boolean", "Whether the upload was rejected"),
    ),
    description = "Ingestion bomb protection. Stops emitting once totalSeen exceeds the configured limit.",
    hotStateDescription = "Hot = (Long, Boolean)",
  )

  val blockVerifier: TransducerStage = TransducerStage(
    id = "verify",
    name = "Block Verifier",
    inputType = "Chunk[Byte]",
    outputType = "VerifyResult",
    summaryFields = List(
      SummaryField("verified", "Long", "Blocks that passed re-hash verification"),
      SummaryField("failed", "Long", "Blocks that failed verification"),
    ),
    description = "Re-hash each block and compare with the expected BinaryKey.Block from the manifest.",
    hotStateDescription = "Hot = (Long, Long)",
  )

  val manifestBuilder: TransducerStage = TransducerStage(
    id = "manifestBuilder",
    name = "Manifest Builder",
    inputType = "CanonicalBlock",
    outputType = "ManifestEntry",
    summaryFields = List(
      SummaryField("entries", "Long", "Number of manifest entries emitted"),
      SummaryField("manifestSize", "Long", "Estimated serialized manifest size in bytes"),
    ),
    description = "Compute BlobOffset spans and emit ManifestEntry for each canonical block.",
    hotStateDescription = "Hot = (Long, Long) — offset, entryCount",
  )

  /** All known stages, in a stable order. */
  val allStages: List[TransducerStage] = List(
    countBytes,
    hashBytes,
    rechunk,
    blockKeyDeriver,
    dedup,
    blockCounter,
    compress,
    bombGuard,
    blockVerifier,
    manifestBuilder,
  )

  // --- Pre-built pipelines -------------------------------------------------

  val basicIngest: PipelineDescriptor = PipelineDescriptor(
    name = "Basic Ingest",
    description = "Minimum viable CAS ingest: count, hash, rechunk.",
    stages = List(countBytes, hashBytes, rechunk),
    operators = List(CompositionOp.Sequential, CompositionOp.Sequential),
    scalaExpression = "countBytes >>> hashBytes() >>> rechunk(blockSize)",
  )

  val fullCasIngest: PipelineDescriptor = PipelineDescriptor(
    name = "Full CAS Pipeline",
    description = "Production ingest with block keying and deduplication.",
    stages = List(countBytes, hashBytes, rechunk, blockKeyDeriver, dedup),
    operators = List(
      CompositionOp.Sequential,
      CompositionOp.Sequential,
      CompositionOp.Sequential,
      CompositionOp.Sequential,
    ),
    scalaExpression = "countBytes >>> hashBytes() >>> rechunk(blockSize) >>> blockKeyDeriver >>> dedup",
  )

  val safeIngest: PipelineDescriptor = PipelineDescriptor(
    name = "Safe Ingest",
    description = "Upload bomb protection followed by ingest with compression.",
    stages = List(bombGuard, countBytes, hashBytes, rechunk, compress),
    operators = List(
      CompositionOp.Sequential,
      CompositionOp.Sequential,
      CompositionOp.Sequential,
      CompositionOp.Sequential,
    ),
    scalaExpression = "bombGuard(maxBytes) >>> countBytes >>> hashBytes() >>> rechunk(blockSize) >>> compress",
  )

  val verifyPipeline: PipelineDescriptor = PipelineDescriptor(
    name = "Verify + Hash",
    description = "Integrity verification with fanout: count, hash, and verify in parallel.",
    stages = List(countBytes, hashBytes, blockVerifier),
    operators = List(CompositionOp.Fanout, CompositionOp.Fanout),
    scalaExpression = "countBytes &&& hashBytes() &&& blockVerifier(manifest)",
  )

  val allPipelines: List[PipelineDescriptor] = List(
    basicIngest,
    fullCasIngest,
    safeIngest,
    verifyPipeline,
  )

end PipelineCatalog
