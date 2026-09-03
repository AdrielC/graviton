package graviton.integration.shardcake

import graviton.core.attributes.IngestStats
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.runtime.upload.*
import zio.Chunk
import zio.blocks.schema.Schema
import zio.blocks.schema.msgpack.{MessagePackCodec, MessagePackFormat}

private[shardcake] object UploadResultCodec:
  inline private val MaxBytes = 64 * 1024

  private final case class WireResult(
    blobKey: String,
    totalBytes: Long,
    blockCount: Int,
    freshBlocks: Int,
    duplicateBlocks: Int,
    durationSeconds: Double,
    ownerId: String,
    ownerHost: String,
    ownerControlPort: Int,
    ownerUploadPort: Int,
  )

  private object WireResult:
    given Schema[WireResult] = Schema.derived

  sealed trait Error extends Throwable

  object Error:
    final case class TooLarge(actual: Int) extends Error:
      override def getMessage: String = s"upload result envelope is $actual bytes; maximum is $MaxBytes"

    final case class Invalid(reason: String) extends Error:
      override def getMessage: String = s"invalid upload result envelope: $reason"

  private val codec: MessagePackCodec[WireResult] = summon[Schema[WireResult]].derive(MessagePackFormat)

  def encode(result: LocalizedUploadResult): Either[Error, Chunk[Byte]] =
    val wire    = WireResult(
      blobKey = result.key.bits.render,
      totalBytes = result.stats.totalBytes,
      blockCount = result.stats.blockCount,
      freshBlocks = result.stats.freshBlocks,
      duplicateBlocks = result.stats.duplicateBlocks,
      durationSeconds = result.stats.durationSeconds,
      ownerId = result.owner.id.value,
      ownerHost = result.owner.host.value,
      ownerControlPort = result.owner.controlPort.value,
      ownerUploadPort = result.owner.uploadPort.value,
    )
    val encoded = Chunk.fromArray(codec.encode(wire))
    Either.cond(encoded.length <= MaxBytes, encoded, Error.TooLarge(encoded.length))

  def decode(bytes: Chunk[Byte]): Either[Error, LocalizedUploadResult] =
    if bytes.length > MaxBytes then Left(Error.TooLarge(bytes.length))
    else
      codec.decode(bytes.toArray).left.map(error => Error.Invalid(error.getMessage)).flatMap { wire =>
        for
          bits        <- KeyBits.parse(wire.blobKey).left.map(Error.Invalid.apply)
          key         <- BinaryKey.blob(bits).left.map(Error.Invalid.apply)
          host        <- UploadNodeHost.either(wire.ownerHost).left.map(Error.Invalid.apply)
          controlPort <- UploadNodePort.either(wire.ownerControlPort).left.map(Error.Invalid.apply)
          uploadPort  <- UploadNodePort.either(wire.ownerUploadPort).left.map(Error.Invalid.apply)
          owner        = UploadNode.fromEndpoints(host, controlPort, uploadPort)
          _           <- Either.cond(owner.id.value == wire.ownerId, (), Error.Invalid("owner ID is not canonical"))
          _           <- Either.cond(
                           wire.totalBytes >= 0L && wire.blockCount >= 0 && wire.freshBlocks >= 0 &&
                             wire.duplicateBlocks >= 0 && wire.durationSeconds >= 0.0 &&
                             wire.freshBlocks + wire.duplicateBlocks == wire.blockCount,
                           (),
                           Error.Invalid("ingest statistics are inconsistent"),
                         )
        yield LocalizedUploadResult(
          key,
          IngestStats(
            wire.totalBytes,
            wire.blockCount,
            wire.freshBlocks,
            wire.duplicateBlocks,
            wire.durationSeconds,
          ),
          owner,
        )
      }
