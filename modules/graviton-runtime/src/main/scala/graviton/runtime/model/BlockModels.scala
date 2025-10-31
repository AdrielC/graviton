package graviton.runtime.model

import graviton.core.attributes.BinaryAttributes
import graviton.core.keys.BinaryKey
import graviton.core.types.{BlockIndex, BlockSize, CompressionLevel, KekId, MaxBlockBytes, NonceLength, Size}
import graviton.core.types.given
import zio.Chunk
import zio.schema.{DeriveSchema, Schema}

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

  private[model] def refineBlockSize(value: Int): Either[String, BlockSize] =
    if value <= 0 then Left(s"Canonical block must be positive in size, received $value bytes")
    else if value > MaxBlockBytes then Left(s"Canonical block exceeds $MaxBlockBytes bytes (got $value)")
    else Right(value.asInstanceOf[BlockSize])

enum BlockStoredStatus:
  case Fresh
  case Duplicate
  case Forwarded

object BlockStoredStatus:
  given Schema[BlockStoredStatus] = DeriveSchema.gen[BlockStoredStatus]

final case class StoredBlock(
  key: BinaryKey.Block,
  size: BlockSize,
  status: BlockStoredStatus,
)

object StoredBlock:
  given Schema[StoredBlock] = DeriveSchema.gen[StoredBlock]

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

object FrameAadPlan:
  given Schema[FrameAadPlan] = DeriveSchema.gen[FrameAadPlan]

sealed trait CompressionPlan
object CompressionPlan:
  case object Disabled                                                              extends CompressionPlan
  final case class Zstd(level: CompressionLevel, dictionary: Option[String] = None) extends CompressionPlan

  given Schema[CompressionPlan] = DeriveSchema.gen[CompressionPlan]

enum FrameLayout:
  case BlockPerFrame
  case Aggregate(maxBlocksPerFrame: Int)

object FrameLayout:
  given Schema[FrameLayout] = DeriveSchema.gen[FrameLayout]

sealed trait EncryptionPlan
object EncryptionPlan:
  case object Disabled extends EncryptionPlan
  final case class Aead(
    mode: EncryptionMode,
    keyId: KekId,
    nonceLength: NonceLength,
    aad: FrameAadPlan = FrameAadPlan(),
  ) extends EncryptionPlan

  given Schema[EncryptionPlan] = DeriveSchema.gen[EncryptionPlan]

enum EncryptionMode:
  case XChaCha20Poly1305
  case Aes256Gcm

object EncryptionMode:
  given Schema[EncryptionMode] = DeriveSchema.gen[EncryptionMode]

final case class FrameSynthesis(
  layout: FrameLayout = FrameLayout.BlockPerFrame,
  compression: CompressionPlan = CompressionPlan.Disabled,
  encryption: EncryptionPlan = EncryptionPlan.Disabled,
)

object FrameSynthesis:
  val default: FrameSynthesis  = FrameSynthesis()
  given Schema[FrameSynthesis] = DeriveSchema.gen[FrameSynthesis]

final case class BlockBatchResult(
  manifest: BlockManifest,
  stored: Chunk[StoredBlock],
  forward: Chunk[CanonicalBlock] = Chunk.empty,
  frames: Chunk[BlockFrame] = Chunk.empty,
)

object BlockBatchResult

final case class BlockWritePlan(
  frame: FrameSynthesis = FrameSynthesis.default,
  forwardDuplicates: Boolean = false,
)

object BlockWritePlan

enum FrameType:
  case Block
  case Manifest
  case Attribute
  case Index

object FrameType:
  given Schema[FrameType] = DeriveSchema.gen[FrameType]

enum FrameAlgorithm:
  case Plain
  case Compressed
  case Encrypted
  case CompressedThenEncrypted

object FrameAlgorithm:
  given Schema[FrameAlgorithm] = DeriveSchema.gen[FrameAlgorithm]

final case class FrameHeader(
  version: Byte,
  frameType: FrameType,
  algorithm: FrameAlgorithm,
  payloadLength: Long,
  aadLength: Int,
  keyId: Option[String] = None,
  nonce: Option[Chunk[Byte]] = None,
)

object FrameHeader:
  given Schema[FrameHeader] = DeriveSchema.gen[FrameHeader]

final case class FrameAadEntry(key: String, value: String)

object FrameAadEntry:
  given Schema[FrameAadEntry] = DeriveSchema.gen[FrameAadEntry]

final case class FrameAad(
  orgId: Option[String],
  blobKey: Option[BinaryKey],
  blockIndex: Option[Long],
  policyTag: Option[String],
  extra: Chunk[FrameAadEntry],
)

object FrameAad:
  given Schema[FrameAad] = DeriveSchema.gen[FrameAad]

final case class FrameContext(
  orgId: Option[String] = None,
  blobKey: Option[BinaryKey.Blob] = None,
  blockIndex: Long = 0L,
  policyTag: Option[String] = None,
)

object FrameContext:
  given Schema[FrameContext] = DeriveSchema.gen[FrameContext]

final case class BlockFrame(
  header: FrameHeader,
  aad: FrameAad,
  aadBytes: Chunk[Byte],
  ciphertext: Chunk[Byte],
  tag: Option[Chunk[Byte]] = None,
)

object BlockFrame:
  given Schema[BlockFrame] = DeriveSchema.gen[BlockFrame]
