package com.yourorg.graviton.client

import com.google.protobuf.ByteString
import com.yourorg.graviton.client.GravitonUploadGatewayClientZIO.*
import io.grpc.Status
import io.graviton.blobstore.v1.common.*
import io.graviton.blobstore.v1.upload.*
import zio.*
import zio.stream.*
import zio.test.*

object GravitonUploadGatewayClientZIOSpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment, Any] =
    suite("GravitonUploadGatewayClientZIO")(
      test("uploadFrames streams frames, enforces ack ordering, and returns completion") {
        for {
          recorded <- Ref.make(Chunk.empty[ClientFrame])
          gateway   = RecordingGateway(
                        recorded,
                        Chunk(
                          ServerFrame(
                            ServerFrame.Kind.StartAck(
                              StartAck(sessionId = "sess-1", ttlSeconds = 60L, acceptedContentTypes = List("application/graviton-frame"))
                            )
                          ),
                          ServerFrame(ServerFrame.Kind.Ack(Ack(sessionId = "sess-1", acknowledgedSequence = 0L, receivedBytes = 8L))),
                          ServerFrame(ServerFrame.Kind.Ack(Ack(sessionId = "sess-1", acknowledgedSequence = 1L, receivedBytes = 16L))),
                          ServerFrame(
                            ServerFrame.Kind.Completed(
                              Completed(
                                sessionId = "sess-1",
                                documentId = "doc-1",
                                blobHash = "hash-123",
                                objectContentType = "application/pdf",
                                finalUrl = Some("https://example"),
                              )
                            )
                          ),
                        ),
                      )
          uploadSvc = new InMemoryUploadService
          client    = new GravitonUploadGatewayClientZIO(gateway, uploadSvc)(using Clock.ClockLive)
          frame1    = DataFrame(
                        "sess-1",
                        sequence = 0L,
                        offsetBytes = 0L,
                        contentType = "application/graviton-frame",
                        bytes = ByteString.copyFrom("frame-0".getBytes),
                        last = false,
                      )
          frame2    = DataFrame(
                        "sess-1",
                        sequence = 1L,
                        offsetBytes = 6L,
                        contentType = "application/graviton-frame",
                        bytes = ByteString.copyFrom("frame-1".getBytes),
                        last = true,
                      )
          outcome  <- client.uploadFrames(
                        start = StartUpload(objectContentType = "application/pdf", metadata = List.empty),
                        frames = ZStream.fromIterable(Chunk(Left(frame1), Left(frame2))),
                        complete = Complete(sessionId = "sess-1"),
                      )
          sent     <- recorded.get
        } yield assertTrue(
          sent.collect { case ClientFrame(kind = ClientFrame.Kind.Frame(df)) => df.sequence } == Chunk(0L, 1L),
          outcome.acknowledgements.map(_.acknowledgedSequence) == Chunk(0L, 1L),
          outcome.completed.blobHash == "hash-123",
        )
      },
      test("uploadFrames fails fast on TTL expiry") {
        val responses = Chunk(
          ServerFrame(ServerFrame.Kind.StartAck(StartAck(sessionId = "ttl", ttlSeconds = 1L))),
          ServerFrame(
            ServerFrame.Kind.Error(Error(code = Error.Code.SESSION_EXPIRED, message = "expired", details = Map("reason" -> "ttl")))
          ),
        )
        val client    = new GravitonUploadGatewayClientZIO(RecordingGateway.drain(responses), new InMemoryUploadService)(using Clock.ClockLive)
        client
          .uploadFrames(
            StartUpload(objectContentType = "application/octet-stream", metadata = List.empty),
            ZStream.empty,
            complete = Complete(sessionId = "ttl"),
          )
          .exit
          .map(exit => assertTrue(exit.isFailure))
      },
      test("uploadFrames surfaces metadata validation events") {
        val responses = Chunk(
          ServerFrame(ServerFrame.Kind.StartAck(StartAck(sessionId = "meta", ttlSeconds = 30L))),
          ServerFrame(
            ServerFrame.Kind.Event(Event(topic = "metadata.validation", message = "schema", attributes = Map("validation" -> "true")))
          ),
          ServerFrame(
            ServerFrame.Kind.Completed(
              Completed(sessionId = "meta", documentId = "doc", blobHash = "hash", objectContentType = "application/pdf", finalUrl = None)
            )
          ),
        )
        val client    = new GravitonUploadGatewayClientZIO(RecordingGateway.drain(responses), new InMemoryUploadService)(using Clock.ClockLive)
        client
          .uploadFrames(
            StartUpload(objectContentType = "application/pdf", metadata = List.empty),
            ZStream.empty,
            complete = Complete(sessionId = "meta"),
          )
          .map(outcome => assertTrue(outcome.events.exists(_.attributes.get("validation").contains("true"))))
      },
      test("uploadFrames detects out-of-order acknowledgements") {
        val responses = Chunk(
          ServerFrame(ServerFrame.Kind.StartAck(StartAck(sessionId = "ooo", ttlSeconds = 30L))),
          ServerFrame(ServerFrame.Kind.Ack(Ack(sessionId = "ooo", acknowledgedSequence = 2L, receivedBytes = 1L))),
        )
        val client    = new GravitonUploadGatewayClientZIO(RecordingGateway.drain(responses), new InMemoryUploadService)(using Clock.ClockLive)
        client
          .uploadFrames(
            StartUpload(objectContentType = "application/octet-stream", metadata = List.empty),
            ZStream.empty,
            complete = Complete(sessionId = "ooo"),
          )
          .exit
          .map(exit => assertTrue(exit.isFailure))
      },
    )

  private final case class RecordingGateway(ref: Ref[Chunk[ClientFrame]], responses: Chunk[ServerFrame]) extends UploadGatewayClient {
    override def stream(requests: ZStream[Any, Throwable, ClientFrame]): ZStream[Any, Status, ServerFrame] =
      ZStream.unwrap {
        requests.runCollect
          .mapError(Status.fromThrowable)
          .flatMap { collected =>
            ref.set(collected) *> ZIO.succeed(ZStream.fromChunk(responses))
          }
      }
  }

  private object RecordingGateway {
    def drain(responses: Chunk[ServerFrame]): UploadGatewayClient =
      new UploadGatewayClient {
        override def stream(requests: ZStream[Any, Throwable, ClientFrame]): ZStream[Any, Status, ServerFrame] =
          ZStream.unwrap(
            requests.runDrain
              .mapError(Status.fromThrowable)
              .as(ZStream.fromChunk(responses))
          )
      }
  }

  private final class InMemoryUploadService extends UploadServiceClient {
    override def registerUpload(request: RegisterUploadRequest): IO[Status, RegisterUploadResponse] =
      ZIO.succeed(RegisterUploadResponse(sessionId = request.clientSessionId.getOrElse("session"), ttlSeconds = 30L))

    override def uploadParts(request: ZStream[Any, Throwable, UploadPartRequest]): ZStream[Any, Status, UploadPartsResponse] =
      ZStream.unwrap(
        request.runDrain
          .mapError(Status.fromThrowable)
          .as(ZStream.empty: ZStream[Any, Status, UploadPartsResponse])
      )

    override def completeUpload(request: CompleteUploadRequest): IO[Status, CompleteUploadResponse] =
      ZIO.succeed(
        CompleteUploadResponse(
          documentId = "doc",
          blobHash = request.expectedObjectHash.getOrElse("hash"),
          objectContentType = "application/octet-stream",
          finalUrl = None,
        )
      )
  }
}
