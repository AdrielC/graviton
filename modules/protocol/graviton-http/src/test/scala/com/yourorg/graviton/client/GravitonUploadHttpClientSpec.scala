package com.yourorg.graviton.client

import com.yourorg.graviton.client.GravitonUploadGatewayClientZIO.UploadGatewayClient
import io.grpc.Status
import io.graviton.blobstore.v1.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*
import zio.test.*

object GravitonUploadHttpClientSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("GravitonUploadHttpClient")(
      test("HTTP upload parity matches frames upload hash") {
        val expectedHash = "hash-parity"
        for {
          recorded <- Ref.make(Chunk.empty[ClientFrame])
          gateway   = RecordingGateway(recorded,
                        Chunk(
                          ServerFrame(ServerFrame.Kind.StartAck(StartAck(sessionId = "sess", ttlSeconds = 60L))),
                          ServerFrame(ServerFrame.Kind.Ack(Ack(sessionId = "sess", acknowledgedSequence = 0L, receivedBytes = 5L))),
                          ServerFrame(ServerFrame.Kind.Completed(Completed(sessionId = "sess", documentId = "doc", blobHash = expectedHash, objectContentType = "application/octet-stream"))),
                        ))
            uploadSvc = new InMemoryUploadService
          gatewayClient = new GravitonUploadGatewayClientZIO(gateway, uploadSvc)
          outcome <- gatewayClient.uploadFrames(
                       StartUpload(objectContentType = "application/octet-stream", metadata = List.empty),
                       ZStream.succeed(Left(DataFrame(sessionId = "sess", sequence = 0L, offsetBytes = 0L, contentType = "application/graviton-frame", bytes = Chunk.fromArray("bytes".getBytes), last = true))),
                       complete = Complete(sessionId = "sess"),
                     )
          calls    <- Ref.make(Vector.empty[HttpCall])
          transport = HttpTransportStub(expectedHash, calls)
          httpClient = new GravitonUploadHttpClient(URL.decode("http://localhost:8080").toOption.get())(transport.apply)
          complete  <- httpClient.uploadOneShot(
                         GravitonUploadHttpClient.RegisterRequest(
                           objectContentType = "application/octet-stream",
                           totalSize = Some(5L),
                           metadata = Chunk.empty,
                           clientSessionId = Some("sess"),
                         ),
                         ZStream.fromChunk(Chunk.fromArray("bytes".getBytes)),
                         checksum = Some(expectedHash),
                       )
          history  <- calls.get
        } yield assertTrue(
          outcome.completed.blobHash == expectedHash,
          complete.blobHash == expectedHash,
          history.exists(call => call.path.toString.contains("complete")),
        )
      }
    )

  private final case class HttpCall(method: Method, path: Path, body: String, headers: Headers)

  private final case class HttpTransportStub(blobHash: String, ref: Ref[Vector[HttpCall]])(using Runtime[Any]) extends (Request => Task[Response]):
    private val registerResponse = Json.Obj(
      "sessionId" -> Json.Str("sess"),
      "ttlSeconds" -> Json.Num(60),
    ).toJson

    private val ackResponse = Json.Obj(
      "sessionId" -> Json.Str("sess"),
      "acknowledgedSequence" -> Json.Num(0),
      "receivedBytes" -> Json.Num(5),
    ).toJson

    private def completeResponse = Json.Obj(
      "documentId" -> Json.Str("doc"),
      "blobHash" -> Json.Str(blobHash),
      "objectContentType" -> Json.Str("application/octet-stream"),
    ).toJson

    override def apply(request: Request): Task[Response] =
      for {
        body <- request.body.asString
        _    <- ref.update(_ :+ HttpCall(request.method, request.url.path, body, request.headers))
        response <- ZIO.succeed {
          if request.method == Method.POST && request.url.path.asString.endsWith("/complete") then Response.json(completeResponse)
          else if request.method == Method.POST then Response.json(registerResponse)
          else Response.json(ackResponse)
        }
      } yield response

    private final case class RecordingGateway(ref: Ref[Chunk[ClientFrame]], responses: Chunk[ServerFrame]) extends UploadGatewayClient:
      override def stream(requests: ZStream[Any, Throwable, ClientFrame]): ZStream[Any, Status, ServerFrame] =
        ZStream.unwrap {
          requests.runCollect.flatMap { collected =>
            ref.set(collected) *> ZIO.succeed(ZStream.fromChunk(responses))
          }
        }

    private final class InMemoryUploadService extends GravitonUploadGatewayClientZIO.UploadServiceClient:
      override def registerUpload(request: RegisterUploadRequest): IO[Status, RegisterUploadResponse] =
        ZIO.succeed(RegisterUploadResponse(sessionId = request.clientSessionId.getOrElse("session"), ttlSeconds = 30L))

      override def uploadParts(request: ZStream[Any, Throwable, UploadPartRequest]): ZStream[Any, Status, UploadPartsResponse] =
        ZStream.unwrap(request.runDrain.as(ZStream.empty))

      override def completeUpload(request: CompleteUploadRequest): IO[Status, CompleteUploadResponse] =
        ZIO.succeed(CompleteUploadResponse(documentId = "doc", blobHash = request.expectedObjectHash.getOrElse("hash"), objectContentType = "application/octet-stream"))
