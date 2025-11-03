package graviton.ingest

import graviton.*
import zio.*
import graviton.chunking.*

sealed trait CompressionAlgo
object CompressionAlgo:
  case object None extends CompressionAlgo

sealed trait EncryptionAlgo
object EncryptionAlgo:
  case object None extends EncryptionAlgo

final case class FramingConfig(
  version: Byte = 1.toByte,
  truncatedHashBytes: Int = 8,
  aad: Chunk[Byte] = Chunk.empty,
  keyId: Option[Chunk[Byte]] = None,
)

final case class ChunkerConfig(
  tokenPack: AnchoredCdcPipeline.TokenPack,
  avgSize: Int,
  anchorBonus: Int,
)

final case class IngestConfig(
  hashAlgorithm: HashAlgorithm,
  compression: CompressionAlgo,
  encryption: EncryptionAlgo,
  chunker: ChunkerConfig,
  framing: FramingConfig,
  maxBytes: Option[Long] = None,
)

object IngestConfig:
  private val defaultTokenPack = AnchoredCdcPipeline.TokenPack.fromStrings(
    name = "default",
    tokens = List("\n\n", "\r\n\r\n", "\n%%EOF"),
  )

  val default: IngestConfig = IngestConfig(
    hashAlgorithm = HashAlgorithm.Blake3,
    compression = CompressionAlgo.None,
    encryption = EncryptionAlgo.None,
    chunker = ChunkerConfig(defaultTokenPack, avgSize = 1024 * 64, anchorBonus = 32),
    framing = FramingConfig(),
    maxBytes = Some(512L * 1024L * 1024L),
  )

  val fiberRef: FiberRef[IngestConfig] =
    Unsafe.unsafely:
      Runtime.default.unsafe.run(ZIO.scoped(FiberRef.make(IngestConfig.default))).getOrThrowFiberFailure()
