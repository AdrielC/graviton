package ai.hylo.graviton.client

import io.grpc.Status
import io.graviton.blobstore.v1.common.*
import io.graviton.blobstore.v1.upload.*
import zio.*
import zio.stream.*

final class GravitonUploadGatewayClientZIO(
  gateway: GravitonUploadGatewayClientZIO.UploadGatewayClient,
  service: GravitonUploadGatewayClientZIO.UploadServiceClient,
):

  import GravitonUploadGatewayClientZIO.*

  def uploadFrames(
    start: StartUpload,
    frames: ZStream[Any, Throwable, Either[DataFrame, RawChunk]],
    complete: Complete,
    subscribe: Option[Subscribe] = None,
    pingInterval: Option[Duration] = None,
  ): IO[UploadGatewayError, UploadOutcome] = {
    val startStream                                           = ZStream.succeed(ClientFrame(ClientFrame.Kind.Start(start)))
    val subscribeStream: ZStream[Any, Throwable, ClientFrame] =
      subscribe match
        case Some(sub) => ZStream.succeed(ClientFrame(ClientFrame.Kind.Subscribe(sub)))
        case None      => ZStream.empty
    val chunkStream                                           = frames.map {
      case Left(frame) => ClientFrame(ClientFrame.Kind.Frame(frame))
      case Right(raw)  => ClientFrame(ClientFrame.Kind.Chunk(raw))
    }
    val completeStream                                        = ZStream.succeed(ClientFrame(ClientFrame.Kind.Complete(complete)))
    val pingStream: ZStream[Any, Throwable, ClientFrame]      =
      pingInterval match
        case Some(interval) =>
          ZStream.fromSchedule(Schedule.spaced(interval)).map { _ =>
            ClientFrame(ClientFrame.Kind.Ping(Ping(sessionId = start.clientSessionId.getOrElse(""))))
          }
        case None           => ZStream.empty

    val outbound = startStream ++ subscribeStream ++ chunkStream ++ completeStream ++ pingStream

    gateway
      .stream(outbound)
      .mapError(UploadGatewayError.TransportFailure.apply)
      .runCollect
      .flatMap(UploadOutcome.fromFrames)
  }

  def uploadViaClassic(
    request: RegisterUploadRequest,
    parts: Chunk[UploadPartRequest],
    complete: CompleteUploadRequest,
  ): IO[UploadGatewayError, CompleteUploadResponse] =
    for {
      registered <- service.registerUpload(request).mapError(UploadGatewayError.TransportFailure.apply)
      _          <- service
                      .uploadParts(ZStream.fromIterable(parts))
                      .mapError(UploadGatewayError.TransportFailure.apply)
                      .runDrain
      result     <- service
                      .completeUpload(complete.copy(sessionId = registered.sessionId))
                      .mapError(UploadGatewayError.TransportFailure.apply)
    } yield result

object GravitonUploadGatewayClientZIO:

  trait UploadGatewayClient:
    def stream(requests: ZStream[Any, Throwable, ClientFrame]): ZStream[Any, Status, ServerFrame]

  trait UploadServiceClient:
    def registerUpload(request: RegisterUploadRequest): IO[Status, RegisterUploadResponse]
    def uploadParts(request: ZStream[Any, Throwable, UploadPartRequest]): ZStream[Any, Status, UploadPartsResponse]
    def completeUpload(request: CompleteUploadRequest): IO[Status, CompleteUploadResponse]

  sealed trait UploadGatewayError extends Throwable:
    def message: String
    override def getMessage: String = message

  object UploadGatewayError:
    final case class TransportFailure(status: Status) extends UploadGatewayError:
      override def message: String = Option(status.getDescription).getOrElse(status.getCode.name())

    final case class StreamFailure(cause: Throwable) extends UploadGatewayError:
      override def message: String = Option(cause.getMessage).getOrElse("stream failure")

    final case class RemoteFailure(error: Error) extends UploadGatewayError:
      override def message: String = s"${error.code.name}: ${error.message}"

    final case class AckOutOfOrder(previous: Long, observed: Long) extends UploadGatewayError:
      override def message: String = s"Ack sequence $observed is not greater than $previous"

    final case class MissingCompletion(message: String) extends UploadGatewayError

    final case class SessionExpired(details: Option[String]) extends UploadGatewayError:
      override def message: String = details.getOrElse("Upload session expired")

  final case class UploadOutcome(
    startAck: StartAck,
    acknowledgements: Chunk[Ack],
    progress: Chunk[Progress],
    events: Chunk[Event],
    completed: Completed,
  )

  object UploadOutcome:
    private final case class Accumulator(
      startAck: Option[StartAck] = None,
      lastAck: Long = -1L,
      acknowledgements: Chunk[Ack] = Chunk.empty,
      progress: Chunk[Progress] = Chunk.empty,
      events: Chunk[Event] = Chunk.empty,
      completed: Option[Completed] = None,
    )

    def fromFrames(frames: Chunk[ServerFrame]): IO[UploadGatewayError, UploadOutcome] =
      ZIO
        .foldLeft(frames)(Accumulator()) { (state, frame) =>
          frame.kind match
            case ServerFrame.Kind.StartAck(value)  => ZIO.succeed(state.copy(startAck = Some(value)))
            case ServerFrame.Kind.Ack(value)       =>
              if value.acknowledgedSequence <= state.lastAck then
                ZIO.fail(UploadGatewayError.AckOutOfOrder(state.lastAck, value.acknowledgedSequence))
              else ZIO.succeed(state.copy(lastAck = value.acknowledgedSequence, acknowledgements = state.acknowledgements :+ value))
            case ServerFrame.Kind.Progress(value)  => ZIO.succeed(state.copy(progress = state.progress :+ value))
            case ServerFrame.Kind.Completed(value) => ZIO.succeed(state.copy(completed = Some(value)))
            case ServerFrame.Kind.Event(value)     => ZIO.succeed(state.copy(events = state.events :+ value))
            case ServerFrame.Kind.Error(value)     =>
              value.code match
                case Error.Code.SESSION_EXPIRED => ZIO.fail(UploadGatewayError.SessionExpired(value.details.get("reason")))
                case _                          => ZIO.fail(UploadGatewayError.RemoteFailure(value))
            case ServerFrame.Kind.Pong(_)          => ZIO.succeed(state)
            case ServerFrame.Kind.Empty            => ZIO.succeed(state)
        }
        .flatMap { acc =>
          (acc.startAck, acc.completed) match
            case (Some(startAck), Some(completed)) =>
              ZIO.succeed(UploadOutcome(startAck, acc.acknowledgements, acc.progress, acc.events, completed))
            case (None, _)                         => ZIO.fail(UploadGatewayError.MissingCompletion("Upload did not receive StartAck"))
            case (_, None)                         => ZIO.fail(UploadGatewayError.MissingCompletion("Upload did not complete"))
        }
