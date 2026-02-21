package graviton.shared.cas

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import zio.json.*

// Iron + zio-json interop: lift base codecs to refined types
given [A, C](using enc: JsonEncoder[A]): JsonEncoder[A :| C] =
  enc.asInstanceOf[JsonEncoder[A :| C]]

given [A, C](using dec: JsonDecoder[A]): JsonDecoder[A :| C] =
  dec.asInstanceOf[JsonDecoder[A :| C]]

given [A, C](using JsonCodec[A]): JsonFieldEncoder[A :| C] =
  JsonFieldEncoder.string.contramap[A :| C](_.toString)

given [A, C](using JsonCodec[A]): JsonFieldDecoder[A :| C] =
  JsonFieldDecoder.string.mapOrFail[A :| C](s =>
    try Right(s.asInstanceOf[A :| C])
    catch case e: Exception => Left(e.getMessage)
  )

/**
 * Cross-compiled CAS (Content-Addressed Storage) model.
 *
 * These types are the shared source of truth for how Graviton models
 * chunking, hashing, and deduplication. Both the JVM runtime and the
 * Scala.js frontend import from here, ensuring the browser playground
 * visualizes the same invariants the real pipeline enforces.
 *
 * Iron refined types push structure into the type level:
 *   - block sizes are bounded `[1, 16 MiB]`
 *   - hex digests are non-empty lowercase hex strings
 *   - block indices are non-negative
 */

// ---------------------------------------------------------------------------
//  Refined types with Iron
// ---------------------------------------------------------------------------

/** Block size in bytes. Mirrors `graviton.core.types.BlockSize`. */
type BlockSizeR = Int :| GreaterEqual[1]

/** Non-negative block index. */
type BlockIndexR = Int :| GreaterEqual[0]

/** Non-negative byte count. */
type ByteCountR = Long :| GreaterEqual[0L]

object CasTypes:
  def blockSize(value: Int): Either[String, BlockSizeR] =
    if value >= 1 && value <= 16777216 then Right(value.asInstanceOf[BlockSizeR])
    else Left(s"BlockSize must be in [1, 16777216], got $value")

  def blockSizeUnsafe(value: Int): BlockSizeR =
    value.asInstanceOf[BlockSizeR]

  def blockIndex(value: Int): Either[String, BlockIndexR] =
    if value >= 0 then Right(value.asInstanceOf[BlockIndexR])
    else Left(s"BlockIndex must be >= 0, got $value")

  def blockIndexUnsafe(value: Int): BlockIndexR =
    value.asInstanceOf[BlockIndexR]

  def byteCount(value: Long): Either[String, ByteCountR] =
    if value >= 0L then Right(value.asInstanceOf[ByteCountR])
    else Left(s"ByteCount must be >= 0, got $value")

  def byteCountUnsafe(value: Long): ByteCountR =
    value.asInstanceOf[ByteCountR]

// ---------------------------------------------------------------------------
//  Hash algorithm descriptor
// ---------------------------------------------------------------------------

/** Known hash algorithms, matching `graviton.core.bytes.HashAlgo`. */
enum HashAlgoDescriptor derives JsonCodec:
  case Sha256
  case Blake3
  case Sha256Sim

  def label: String = this match
    case Sha256    => "sha-256"
    case Blake3    => "blake3"
    case Sha256Sim => "sha256-sim"

// ---------------------------------------------------------------------------
//  Hex digest (value class for type safety)
// ---------------------------------------------------------------------------

/**
 * Hex-encoded content digest.
 *
 * Wraps a non-empty lowercase hex string. Iron validates the format.
 */
final case class HexDigest private (value: String) derives JsonCodec:
  def short: String     = value.take(12)
  def truncated: String = if value.length > 16 then value.take(16) + "..." else value

object HexDigest:
  def fromString(hex: String): Either[String, HexDigest] =
    hex.refineEither[MinLength[1]].map(_ => HexDigest(hex))

  def unsafe(hex: String): HexDigest = HexDigest(hex)

// ---------------------------------------------------------------------------
//  Domain models
// ---------------------------------------------------------------------------

/** A single block produced by the chunking stage. */
final case class CasBlock(
  index: BlockIndexR,
  size: BlockSizeR,
  digest: HexDigest,
  isDuplicate: Boolean,
) derives JsonCodec:
  def shortDigest: String = digest.short

/** Summary of an ingest operation. */
final case class IngestSummary(
  totalBytes: ByteCountR,
  blockCount: BlockIndexR,
  uniqueBlocks: BlockIndexR,
  duplicateBlocks: BlockIndexR,
  blobDigest: HexDigest,
  algo: HashAlgoDescriptor,
) derives JsonCodec:
  def dedupRatio: Double =
    if blockCount == 0 then 0.0
    else duplicateBlocks.toDouble / blockCount.toDouble

/** Describes how a chunking algorithm splits input. */
final case class ChunkingConfig(
  blockSize: BlockSizeR,
  algo: HashAlgoDescriptor,
  strategy: ChunkingStrategy,
) derives JsonCodec

/** Chunking strategies mirroring `graviton.streams.Chunker`. */
enum ChunkingStrategy derives JsonCodec:
  case Fixed
  case FastCdc(minSize: BlockSizeR, maxSize: BlockSizeR)

// ---------------------------------------------------------------------------
//  CAS simulation engine (pure, runs on JVM and JS)
// ---------------------------------------------------------------------------

/**
 * Pure CAS ingest simulation.
 *
 * Mirrors the structure of `CasBlobStore.put()` without any ZIO dependencies.
 * The frontend uses this to visualize real CAS behaviour; the JVM side can
 * use it for documentation examples and snapshot tests.
 *
 * Ingest pipeline structure (same as JVM):
 * {{{
 * input bytes → rechunk(blockSize) → perBlockHash → dedupCheck → IngestSummary
 * }}}
 */
object CasSimulator:

  /**
   * Simulate a CAS ingest of raw bytes with fixed-size chunking.
   *
   * @param input      raw bytes to ingest
   * @param blockSize  target block size (Iron-refined ≥ 1)
   * @param algo       hash algorithm descriptor
   * @param history    set of previously seen block digests (cross-ingest dedup)
   * @return           (ingest summary, list of blocks, updated history)
   */
  def ingest(
    input: Array[Byte],
    blockSize: BlockSizeR,
    algo: HashAlgoDescriptor,
    history: Set[HexDigest],
  ): (IngestSummary, List[CasBlock], Set[HexDigest]) =
    if input.isEmpty then
      val emptyDigest = HexDigest.unsafe(deterministicHash(input))
      val summary     = IngestSummary(
        totalBytes = CasTypes.byteCountUnsafe(0L),
        blockCount = CasTypes.blockIndexUnsafe(0),
        uniqueBlocks = CasTypes.blockIndexUnsafe(0),
        duplicateBlocks = CasTypes.blockIndexUnsafe(0),
        blobDigest = emptyDigest,
        algo = algo,
      )
      (summary, Nil, history)
    else
      val chunks         = input.grouped(blockSize: Int).toList
      var updatedHistory = history
      var unique         = 0
      var duplicate      = 0

      val blocks = chunks.zipWithIndex.map { case (chunk, idx) =>
        val digest = HexDigest.unsafe(deterministicHash(chunk))
        val isDup  = updatedHistory.contains(digest)
        if !isDup then
          updatedHistory = updatedHistory + digest
          unique += 1
        else duplicate += 1
        CasBlock(
          index = CasTypes.blockIndexUnsafe(idx),
          size = CasTypes.blockSizeUnsafe(chunk.length),
          digest = digest,
          isDuplicate = isDup,
        )
      }

      val blobDigest = HexDigest.unsafe(deterministicHash(input))
      val summary    = IngestSummary(
        totalBytes = CasTypes.byteCountUnsafe(input.length.toLong),
        blockCount = CasTypes.blockIndexUnsafe(blocks.length),
        uniqueBlocks = CasTypes.blockIndexUnsafe(unique),
        duplicateBlocks = CasTypes.blockIndexUnsafe(duplicate),
        blobDigest = blobDigest,
        algo = algo,
      )

      (summary, blocks, updatedHistory)

  /**
   * Deterministic hash for cross-platform use.
   *
   * Produces a 32-char hex string. Not cryptographic — used for the browser
   * playground. The real pipeline uses BLAKE3/SHA-256 via `Hasher`.
   */
  def deterministicHash(data: Array[Byte]): String =
    var h1 = 0x9e3779b97f4a7c15L
    var h2 = 0x517cc1b727220a95L
    var i  = 0
    while i < data.length do
      val b = (data(i) & 0xff).toLong
      h1 = h1 * 6364136223846793005L + b
      h2 = h2 * 1442695040888963407L + b + (h1 >>> 17)
      h1 ^= (h2 << 11)
      i += 1
    h1 ^= h1 >>> 33
    h1 *= 0xff51afd7ed558ccdL
    h1 ^= h1 >>> 33
    h2 ^= h2 >>> 29
    h2 *= 0xc4ceb9fe1a85ec53L
    h2 ^= h2 >>> 29
    f"$h1%016x$h2%016x"

end CasSimulator
