package graviton.shared.cas

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.zioJson.given
import pt.kcry.sha.Sha2_256
import zio.json.*

// ---------------------------------------------------------------------------
//  Refined types via Iron's RefinedType
// ---------------------------------------------------------------------------

/**
 * Block size in bytes, ≥ 1.
 * Mirrors `graviton.core.types.BlockSize`.
 */
type BlockSizeR = BlockSizeR.T
object BlockSizeR extends RefinedType[Int, GreaterEqual[1]]

/**
 * Non-negative block index.
 * Mirrors `graviton.core.types.BlockIndex`.
 */
type BlockIndexR = BlockIndexR.T
object BlockIndexR extends RefinedType[Int, GreaterEqual[0]]

/**
 * Non-negative byte count.
 * Mirrors `graviton.core.types.FileSize` (lower bound).
 */
type ByteCountR = ByteCountR.T
object ByteCountR extends RefinedType[Long, GreaterEqual[0L]]

// ---------------------------------------------------------------------------
//  Hash algorithm descriptor
// ---------------------------------------------------------------------------

/** Known hash algorithms, matching `graviton.core.bytes.HashAlgo`. */
enum HashAlgoDescriptor derives JsonCodec:
  case Sha256
  case Blake3

  def label: String = this match
    case Sha256 => "sha-256"
    case Blake3 => "blake3"

// ---------------------------------------------------------------------------
//  Hex digest (value class, validated on construction)
// ---------------------------------------------------------------------------

/**
 * Hex-encoded content digest.
 * Wraps a non-empty lowercase hex string.
 */
final case class HexDigest(value: String :| MinLength[1]) derives JsonCodec:
  def short: String     = (value: String).take(12)
  def truncated: String =
    val s = value: String
    if s.length > 16 then s.take(16) + "..." else s

object HexDigest:
  def fromHex(hex: String): Either[String, HexDigest] =
    hex.refineEither[MinLength[1]].map(HexDigest(_))

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
    val bc = blockCount.value
    if bc == 0 then 0.0
    else duplicateBlocks.value.toDouble / bc.toDouble

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
//  Cross-platform SHA-256
// ---------------------------------------------------------------------------

/**
 * SHA-256 that works identically on JVM, Scala.js, and Scala Native.
 *
 * Uses `pt.kcry:sha` — a dependency-free, cross-compiled SHA-2 implementation.
 * Same library publisher as the `pt.kcry:blake3` already used in graviton-core.
 */
object Sha256Cross:
  def hash(data: Array[Byte]): Array[Byte] =
    Sha2_256.hash(data)

  def hex(data: Array[Byte]): String =
    hash(data).map(b => f"${b & 0xff}%02x").mkString

// ---------------------------------------------------------------------------
//  CAS simulation engine (pure, cross-platform, real SHA-256)
// ---------------------------------------------------------------------------

/**
 * Pure CAS ingest simulation.
 *
 * Mirrors the structure of `CasBlobStore.put()`:
 * {{{
 * input bytes → rechunk(blockSize) → perBlockHash → dedupCheck → IngestSummary
 * }}}
 *
 * Uses real SHA-256 via `pt.kcry:sha` — identical results on JVM and Scala.js.
 */
object CasSimulator:

  def ingest(
    input: Array[Byte],
    blockSize: BlockSizeR,
    history: Set[HexDigest] = Set.empty,
  ): (IngestSummary, List[CasBlock], Set[HexDigest]) =
    if input.isEmpty then
      val emptyDigest = hexDigestOf(input)
      val summary     = IngestSummary(
        totalBytes = ByteCountR.applyUnsafe(0L),
        blockCount = BlockIndexR.applyUnsafe(0),
        uniqueBlocks = BlockIndexR.applyUnsafe(0),
        duplicateBlocks = BlockIndexR.applyUnsafe(0),
        blobDigest = emptyDigest,
        algo = HashAlgoDescriptor.Sha256,
      )
      (summary, Nil, history)
    else
      val chunks         = input.grouped(blockSize.value).toList
      var updatedHistory = history
      var unique         = 0
      var duplicate      = 0

      val blocks = chunks.zipWithIndex.map { case (chunk, idx) =>
        val digest = hexDigestOf(chunk)
        val isDup  = updatedHistory.contains(digest)
        if !isDup then
          updatedHistory = updatedHistory + digest
          unique += 1
        else duplicate += 1
        CasBlock(
          index = BlockIndexR.applyUnsafe(idx),
          size = BlockSizeR.applyUnsafe(chunk.length),
          digest = digest,
          isDuplicate = isDup,
        )
      }

      val blobDigest = hexDigestOf(input)
      val summary    = IngestSummary(
        totalBytes = ByteCountR.applyUnsafe(input.length.toLong),
        blockCount = BlockIndexR.applyUnsafe(blocks.length),
        uniqueBlocks = BlockIndexR.applyUnsafe(unique),
        duplicateBlocks = BlockIndexR.applyUnsafe(duplicate),
        blobDigest = blobDigest,
        algo = HashAlgoDescriptor.Sha256,
      )

      (summary, blocks, updatedHistory)

  private def hexDigestOf(data: Array[Byte]): HexDigest =
    val hex = Sha256Cross.hex(data)
    HexDigest.fromHex(hex) match
      case Right(d)  => d
      case Left(msg) =>
        HexDigest.fromHex("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855") match
          case Right(fallback) => fallback
          case Left(_)         => throw new IllegalStateException(s"SHA-256 produced invalid hex: $msg")

end CasSimulator
