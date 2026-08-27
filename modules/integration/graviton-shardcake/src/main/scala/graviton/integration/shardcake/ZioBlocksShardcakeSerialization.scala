package graviton.integration.shardcake

import com.devsisters.shardcake.Replier
import com.devsisters.shardcake.interfaces.Serialization
import graviton.runtime.upload.*
import zio.*
import zio.blocks.schema.Schema
import zio.blocks.schema.msgpack.{MessagePackCodec, MessagePackFormat}

/**
 * Shardcake control-message serialization derived from ZIO Blocks Schema.
 *
 * Shardcake 2.8.1 fixes its extension ABI to `Array[Byte]`. That representation
 * is isolated to the two overrides below. Every Graviton-facing value is a
 * named domain product and every encoded envelope is capped at 64 KiB before
 * it can cross the boundary.
 */
object ZioBlocksShardcakeSerialization:
  inline private val MaxControlEnvelopeBytes = 64 * 1024

  private final case class WireHotState(
    phase: String,
    framesSeen: Long,
    bytesSeen: Long,
    startedAtNanos: Long,
    lastActivityNanos: Long,
    tenantId: String,
    uploadSessionId: String,
  )

  private object WireHotState:
    given Schema[WireHotState] = Schema.derived

  private enum WireEnvelope:
    case Resolve(replyId: String)
    case Ready(
      nodeId: String,
      host: String,
      controlPort: Int,
      uploadPort: Int,
      hotState: Option[WireHotState],
    )

  private object WireEnvelope:
    given schema: Schema[WireEnvelope] = Schema.derived

  sealed trait Error extends Throwable

  object Error:
    final case class UnsupportedMessage(actual: String) extends Error:
      override def getMessage: String = s"Unsupported Shardcake control message: $actual"

    final case class EnvelopeTooLarge(actual: Int) extends Error:
      override def getMessage: String = s"Shardcake control envelope is $actual bytes; maximum is $MaxControlEnvelopeBytes"

    final case class InvalidEnvelope(reason: String) extends Error:
      override def getMessage: String = s"Invalid Shardcake control envelope: $reason"

  private val codec: MessagePackCodec[WireEnvelope] =
    summon[Schema[WireEnvelope]].derive(MessagePackFormat)

  val layer: ULayer[Serialization] =
    ZLayer.succeed(new Live)

  private final class Live extends Serialization:
    // Upstream ABI seam. Do not expose this representation from Graviton APIs.
    override def encode(message: Any): Task[Array[Byte]] =
      ZIO.attempt {
        val encoded = codec.encode(toWire(message))
        if encoded.length > MaxControlEnvelopeBytes then throw Error.EnvelopeTooLarge(encoded.length)
        encoded
      }

    // Upstream ABI seam. The size is rejected before ZIO Blocks decodes it.
    override def decode[A](bytes: Array[Byte]): Task[A] =
      if bytes.length > MaxControlEnvelopeBytes then ZIO.fail(Error.EnvelopeTooLarge(bytes.length))
      else
        ZIO
          .fromEither(codec.decode(bytes).left.map(error => Error.InvalidEnvelope(error.getMessage)))
          .flatMap(wire => ZIO.fromEither(fromWire(wire)))
          .map(_.asInstanceOf[A])

  private def toWire(message: Any): WireEnvelope =
    message match
      case UploadControlMessage.Resolve(reply)       =>
        WireEnvelope.Resolve(reply.id)
      case UploadControlReply.Ready(owner, hotState) =>
        WireEnvelope.Ready(
          nodeId = owner.id.value,
          host = owner.host.value,
          controlPort = owner.controlPort.value,
          uploadPort = owner.uploadPort.value,
          hotState = hotState.map(snapshot =>
            WireHotState(
              phase = snapshot.phase.toString,
              framesSeen = snapshot.framesSeen,
              bytesSeen = snapshot.bytesSeen,
              startedAtNanos = snapshot.startedAtNanos,
              lastActivityNanos = snapshot.lastActivityNanos,
              tenantId = snapshot.key.tenantId.value,
              uploadSessionId = snapshot.key.uploadSessionId.value,
            )
          ),
        )
      case other                                     =>
        throw Error.UnsupportedMessage(other.getClass.getName)

  private def fromWire(wire: WireEnvelope): Either[Error, Any] =
    wire match
      case WireEnvelope.Resolve(replyId) =>
        Either.cond(
          replyId.nonEmpty && replyId.length <= 128,
          UploadControlMessage.Resolve(Replier(replyId)),
          Error.InvalidEnvelope("reply ID is invalid"),
        )
      case ready: WireEnvelope.Ready     =>
        for
          nodeId      <- UploadNodeId.either(ready.nodeId).left.map(Error.InvalidEnvelope.apply)
          host        <- UploadNodeHost.either(ready.host).left.map(Error.InvalidEnvelope.apply)
          controlPort <- UploadNodePort.either(ready.controlPort).left.map(Error.InvalidEnvelope.apply)
          uploadPort  <- UploadNodePort.either(ready.uploadPort).left.map(Error.InvalidEnvelope.apply)
          node         = UploadNode.fromEndpoints(host, controlPort, uploadPort)
          _           <- Either.cond(nodeId == node.id, (), Error.InvalidEnvelope("node ID is not the canonical endpoint"))
          hot         <- ready.hotState match
                           case None        => Right(None)
                           case Some(value) => decodeHotState(value).map(Some(_))
        yield UploadControlReply.Ready(node, hot)

  private def decodeHotState(wire: WireHotState): Either[Error, UploadHotState.Snapshot] =
    for
      phase   <- wire.phase match
                   case "Active"    => Right(UploadHotState.Phase.Active)
                   case "Completed" => Right(UploadHotState.Phase.Completed)
                   case "Failed"    => Right(UploadHotState.Phase.Failed)
                   case other       => Left(Error.InvalidEnvelope(s"unknown hot-state phase '$other'"))
      tenant  <- TenantId.either(wire.tenantId).left.map(Error.InvalidEnvelope.apply)
      session <- UploadSessionId.either(wire.uploadSessionId).left.map(Error.InvalidEnvelope.apply)
      _       <- Either.cond(
                   wire.framesSeen >= 0L && wire.bytesSeen >= 0L && wire.startedAtNanos >= 0L && wire.lastActivityNanos >= 0L,
                   (),
                   Error.InvalidEnvelope("hot-state counters must be nonnegative"),
                 )
    yield UploadHotState.Snapshot(
      key = UploadSessionKey(tenant, session),
      phase = phase,
      framesSeen = wire.framesSeen,
      bytesSeen = wire.bytesSeen,
      startedAtNanos = wire.startedAtNanos,
      lastActivityNanos = wire.lastActivityNanos,
    )
