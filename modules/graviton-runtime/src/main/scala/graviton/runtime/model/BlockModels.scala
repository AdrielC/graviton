package graviton.runtime.model

import graviton.core.attributes.BinaryAttributes
import graviton.core.keys.BinaryKey
import graviton.core.types.{BlockIndex, BlockSize, CompressionLevel, KekId, NonceLength, Size}
import zio.Chunk

final case class CanonicalBlock private (
  key: BinaryKey.Block,
  size: BlockSize,
  payload: Chunk[Byte],
  attributes: BinaryAttributes,
):
  def bytes: Chunk[Byte]         = payload
  def metadata: BinaryAttributes = attributes
  def normalizedSize: Int        = size

object CanonicalBlock:
  def make(
    key: BinaryKey.Block,
    payload: Chunk[Byte],
    attributes: BinaryAttributes = BinaryAttributes.empty,
  ): Either[String, CanonicalBlock] =
    refineBlockSize(payload.size).map { blockSize =>
      CanonicalBlock(key, blockSize, payload, attributes)
    }

  private val MaxBlockPayloadBytes = 16 * 1024 * 1024

  private[model] def refineBlockSize(value: Int): Either[String, BlockSize] =
    if value <= 0 then Left(s"Canonical block must be positive in size, received $value bytes")
    else if value > MaxBlockPayloadBytes then Left(s"Canonical block exceeds $MaxBlockPayloadBytes bytes (got $value)")
    else Right(value.asInstanceOf[BlockSize])

enum BlockStoredStatus derives CanEqual:
  case Fresh
  case Duplicate
  case Forwarded

final case class StoredBlock(
  key: BinaryKey.Block,
  size: BlockSize,
  status: BlockStoredStatus,
)

final case class BlockManifestEntry(
  index: BlockIndex,
  offset: Size,
  key: BinaryKey.Block,
  size: BlockSize,
)

object BlockManifestEntry:
  def make(
    index: Long,
    offset: Long,
    key: BinaryKey.Block,
    size: Int,
  ): Either[String, BlockManifestEntry] =
    for
      refinedIndex  <- refineBlockIndex(index)
      refinedOffset <- refineNonNegativeSize(offset, field = "offset")
      refinedSize   <- CanonicalBlock.refineBlockSize(size)
    yield BlockManifestEntry(refinedIndex, refinedOffset, key, refinedSize)

  private def refineBlockIndex(value: Long): Either[String, BlockIndex] =
    if value < 0 then Left(s"Block index cannot be negative: $value")
    else Right(value.asInstanceOf[BlockIndex])

  private def refineNonNegativeSize(value: Long, field: String): Either[String, Size] =
    if value < 0 then Left(s"$field cannot be negative: $value")
    else Right(value.asInstanceOf[Size])

final case class BlockManifest private (
  entries: Chunk[BlockManifestEntry],
  totalUncompressedBytes: Long,
):
  def totalUncompressed: Either[String, Size] =
    BlockManifest.refineTotal(totalUncompressedBytes)

object BlockManifest:
  val empty: BlockManifest = BlockManifest(Chunk.empty, 0L)

  def build(entries: Chunk[BlockManifestEntry]): Either[String, BlockManifest] =
    val total =
      entries.foldLeft(0L) { (acc, entry) =>
        acc + entry.size.toLong
      }
    refineTotal(total).map(validTotal => BlockManifest(entries, validTotal))

  private def refineTotal(value: Long): Either[String, Size] =
    if value < 0 then Left(s"Total uncompressed size cannot be negative: $value")
    else Right(value.asInstanceOf[Size])

final case class FrameAadPlan(
  includeOrgId: Boolean = true,
  includeBlobKey: Boolean = true,
  includeBlockIndex: Boolean = true,
  extra: Chunk[(String, String)] = Chunk.empty,
)

sealed trait CompressionPlan derives CanEqual
object CompressionPlan:
  case object Disabled                                                              extends CompressionPlan
  final case class Zstd(level: CompressionLevel, dictionary: Option[String] = None) extends CompressionPlan

enum FrameLayout derives CanEqual:
  case BlockPerFrame
  case Aggregate(maxBlocksPerFrame: Int)

sealed trait EncryptionPlan derives CanEqual
object EncryptionPlan:
  case object Disabled extends EncryptionPlan
  final case class Aead(
    mode: EncryptionMode,
    keyId: KekId,
    nonceLength: NonceLength,
    aad: FrameAadPlan = FrameAadPlan(),
  ) extends EncryptionPlan

enum EncryptionMode derives CanEqual:
  case XChaCha20Poly1305
  case Aes256Gcm

final case class FrameSynthesis(
  layout: FrameLayout = FrameLayout.BlockPerFrame,
  compression: CompressionPlan = CompressionPlan.Disabled,
  encryption: EncryptionPlan = EncryptionPlan.Disabled,
)

object FrameSynthesis:
  val default: FrameSynthesis = FrameSynthesis()

final case class BlockBatchResult(
  manifest: BlockManifest,
  stored: Chunk[StoredBlock],
  forward: Chunk[CanonicalBlock] = Chunk.empty,
)

final case class BlockWritePlan(
  frame: FrameSynthesis = FrameSynthesis.default,
  forwardDuplicates: Boolean = false,
)
