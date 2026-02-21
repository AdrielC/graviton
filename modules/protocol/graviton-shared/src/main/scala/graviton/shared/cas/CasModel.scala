package graviton.shared.cas

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.zioJson.given
import pt.kcry.sha.Sha2_256
import zio.json.*

// ---------------------------------------------------------------------------
//  Constraints — precise shapes mirroring graviton.core.types
// ---------------------------------------------------------------------------

type BlockSizeConstraint = GreaterEqual[1] & LessEqual[16777216]
type HexLowerConstraint  = Match["[0-9a-f]+"] & MinLength[1] & MaxLength[128]
type Sha256HexConstraint = Match["[0-9a-f]{64}"]
type AlgoConstraint      = Match["(sha-256|sha-1|blake3|md5)"]

// ---------------------------------------------------------------------------
//  Iron opaque types — .T is a subtype of the base type
// ---------------------------------------------------------------------------

/** Block size ∈ [1, 16 MiB]. `BlockSize <: Int`. */
type BlockSize = BlockSize.T
object BlockSize extends RefinedSubtype[Int, BlockSizeConstraint]

/** Non-negative block index. `BlockIndex <: Int`. */
type BlockIndex = BlockIndex.T
object BlockIndex extends RefinedSubtype[Int, GreaterEqual[0]]

/** Non-negative byte count. `ByteCount <: Long`. */
type ByteCount = ByteCount.T
object ByteCount extends RefinedSubtype[Long, GreaterEqual[0L]]

/** Lowercase hex, 1–128 chars. `HexDigest <: String`. */
type HexDigest = HexDigest.T
object HexDigest extends RefinedSubtype[String, HexLowerConstraint]

/** SHA-256 digest: exactly 64 lowercase hex chars. `Sha256Hex <: String`. */
type Sha256Hex = Sha256Hex.T
object Sha256Hex extends RefinedSubtype[String, Sha256HexConstraint]

/** Hash algorithm name constrained to known values. `Algo <: String`. */
type Algo = Algo.T
object Algo extends RefinedSubtype[String, AlgoConstraint]

// ---------------------------------------------------------------------------
//  Hash algorithm descriptor (enum)
// ---------------------------------------------------------------------------

enum HashAlgo derives JsonCodec:
  case Sha256
  case Blake3

  def label: String = this match
    case Sha256 => "sha-256"
    case Blake3 => "blake3"

// ---------------------------------------------------------------------------
//  Domain models
// ---------------------------------------------------------------------------

final case class CasBlock(
  index: BlockIndex,
  size: BlockSize,
  digest: Sha256Hex,
  isDuplicate: Boolean,
) derives JsonCodec:
  def shortDigest: String = (digest: String).take(12)

final case class IngestSummary(
  totalBytes: ByteCount,
  blockCount: BlockIndex,
  uniqueBlocks: BlockIndex,
  duplicateBlocks: BlockIndex,
  blobDigest: Sha256Hex,
  algo: HashAlgo,
) derives JsonCodec:
  def dedupRatio: Double =
    if blockCount.value == 0 then 0.0
    else duplicateBlocks.value.toDouble / blockCount.value.toDouble

enum ChunkingStrategy derives JsonCodec:
  case Fixed
  case FastCdc(minSize: BlockSize, maxSize: BlockSize)

// ---------------------------------------------------------------------------
//  Cross-platform SHA-256 (pt.kcry:sha — JVM + JS + Native)
// ---------------------------------------------------------------------------

object Sha256Cross:
  def hash(data: Array[Byte]): Array[Byte] =
    Sha2_256.hash(data)

  def hex(data: Array[Byte]): Sha256Hex =
    val s = hash(data).map(b => f"${b & 0xff}%02x").mkString
    // SAFETY: SHA-256 always produces exactly 64 lowercase hex chars.
    Sha256Hex.applyUnsafe(s)

// ---------------------------------------------------------------------------
//  CAS simulation engine
// ---------------------------------------------------------------------------

/**
 * Mirrors the structure of `CasBlobStore.put()`:
 * {{{
 * input bytes → rechunk(blockSize) → perBlockHash → dedupCheck → IngestSummary
 * }}}
 */
object CasSimulator:

  def ingest(
    input: Array[Byte],
    blockSize: BlockSize,
    history: Set[Sha256Hex] = Set.empty,
  ): (IngestSummary, List[CasBlock], Set[Sha256Hex]) =
    if input.isEmpty then
      val summary = IngestSummary(
        totalBytes = ByteCount.applyUnsafe(0L),
        blockCount = BlockIndex.applyUnsafe(0),
        uniqueBlocks = BlockIndex.applyUnsafe(0),
        duplicateBlocks = BlockIndex.applyUnsafe(0),
        blobDigest = Sha256Cross.hex(input),
        algo = HashAlgo.Sha256,
      )
      (summary, Nil, history)
    else
      val chunks         = input.grouped(blockSize.value).toList
      var updatedHistory = history
      var unique         = 0
      var duplicate      = 0

      val blocks = chunks.zipWithIndex.map { case (chunk, idx) =>
        val digest = Sha256Cross.hex(chunk)
        val isDup  = updatedHistory.contains(digest)
        if !isDup then
          updatedHistory = updatedHistory + digest
          unique += 1
        else duplicate += 1
        CasBlock(
          index = BlockIndex.applyUnsafe(idx),
          size = BlockSize.applyUnsafe(chunk.length),
          digest = digest,
          isDuplicate = isDup,
        )
      }

      val summary = IngestSummary(
        totalBytes = ByteCount.applyUnsafe(input.length.toLong),
        blockCount = BlockIndex.applyUnsafe(blocks.length),
        uniqueBlocks = BlockIndex.applyUnsafe(unique),
        duplicateBlocks = BlockIndex.applyUnsafe(duplicate),
        blobDigest = Sha256Cross.hex(input),
        algo = HashAlgo.Sha256,
      )

      (summary, blocks, updatedHistory)

end CasSimulator
