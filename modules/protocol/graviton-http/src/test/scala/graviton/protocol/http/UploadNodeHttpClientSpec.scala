package graviton.protocol.http

import graviton.runtime.upload.UploadedPart
import java.nio.charset.StandardCharsets
import zio.*
import zio.Chunk
import zio.http.*
import zio.json.*
import zio.stream.ZStream
import zio.test.*

object UploadNodeHttpClientSpec extends ZIOSpecDefault:

  private val baseUrl: URL      = URL.decode("http://localhost:8080").toOption.get
  private val uploadsPath: Path = Path.root / "api" / "uploads"

  override def spec: Spec[TestEnvironment, Any] =
    suite("UploadNodeHttpClient")(
      test("startMultipart posts JSON payload to uploads endpoint") {
        for {
          captured <- Ref.make[Option[Request]](None)
          responses = (request: Request) =>
                        captured.set(Some(request)) *> ZIO.succeed(
                          Response.json(
                            """{"uploadId":"u-123","chunkSize":1048576,"maxChunks":16,"expiresAtEpochSeconds":123}"""
                          )
                        )
          client    = makeClient(baseUrl, uploadsPath, responses)
          request   = MultipartStartRequest(
                        Some("file.bin"),
                        Some(1024L),
                        Some("application/octet-stream"),
                        Map.empty[String, Map[String, String]],
                        None,
                        Map.empty[Int, String],
                        None,
                      )
          session  <- client.startMultipart(request)
          seen     <- captured.get.someOrFailException
        } yield assertTrue(
          session.uploadId == "u-123",
          session.chunkSize == 1048576,
          session.maxChunks == 16,
          seen.method == Method.POST,
          seen.url.path == baseUrl.path ++ uploadsPath,
          seen.headerValue("content-type").exists(_.contains("application/json")),
        )
      },
      test("uploadPart streams bytes and decodes acknowledgements") {
        for {
          captured <- Ref.make[Option[(Request, String)]](None)
          responses = (request: Request) =>
                        for {
                          body <- request.body.asString
                          _    <- captured.set(Some(request -> body))
                        } yield Response.json("""{"partNumber":1,"acknowledgedSequence":1,"receivedBytes":7}""")
                        client = makeClient(baseUrl, uploadsPath, responses)
          session   = MultipartSession("u-123", chunkSize = 1024, maxChunks = 16, expiresAtEpochSeconds = None)
          payload   = ZStream.fromChunk(Chunk.fromArray("payload".getBytes(StandardCharsets.UTF_8)))
          _        <- client.uploadPart(session, MultipartPartRequest(1, 0L, Some(7L), Some("chk")), payload)
          seen     <- captured.get.someOrFailException
        } yield assertTrue(
          seen._1.method == Method.PUT,
          seen._1.url.path == baseUrl.path ++ (uploadsPath / "u-123" / "parts" / "1"),
          seen._1.headerValue("Content-Length") == Some("7"),
          seen._2 == "payload",
        )
      },
      test("complete posts manifest and decodes completion response") {
        for {
          captured <- Ref.make[Option[String]](None)
          responses = (request: Request) =>
                        request.body.asString.flatMap { body =>
                          captured.set(Some(body)) *> ZIO.succeed(Response.json("""{"documentId":"doc-1","attributes":{"zone":"us"}}"""))
                        }
                        client = makeClient(baseUrl, uploadsPath, responses)
          session   = MultipartSession("u-456", chunkSize = 2048, maxChunks = 32, expiresAtEpochSeconds = Some(999L))
          parts     = Chunk(UploadedPart(1, 0L, 7L, Some("chk")))
          result   <- client.complete(session, CompletionRequest(parts, Some("final")))
          body     <- captured.get.someOrFailException
          json     <- ZIO.fromEither(body.fromJson[zio.json.ast.Json])
        } yield assertTrue(
          result.documentId.value == "doc-1",
          result.attributes == Map("zone" -> "us"),
          json.asObject.exists(_.values.exists(_.toString.contains("chk"))),
        )
      },
      test("unexpected status surfaces as error") {
        val transport: Request => Task[Response] = _ =>
          ZIO.succeed(
            Response(
              status = Status.InternalServerError,
              body = Body.fromString("boom"),
            )
          )
        val client                               = makeClient(baseUrl, uploadsPath, transport)
        val session                              = MultipartSession("u-789", chunkSize = 1024, maxChunks = 4, expiresAtEpochSeconds = None)
        client
          .complete(session, CompletionRequest(Chunk.empty, None))
          .exit
          .map(exit => assertTrue(exit.isFailure))
      },
    )

  private def makeClient(
    base: URL,
    prefix: Path,
    transport: Request => Task[Response],
  ): UploadNodeHttpClient =
    new UploadNodeHttpClient(base, prefix, transport)
