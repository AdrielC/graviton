package graviton.protocol.http

import graviton.runtime.Graviton
import graviton.runtime.dashboard.DatalakeDashboardService
import graviton.shared.ApiModels.*
import graviton.shared.dashboard.DashboardSamples
import graviton.shared.schema.SchemaExplorer
import zio.*
import zio.http.*
import zio.json.*
import zio.stream.ZStream
import zio.test.*

import java.nio.charset.StandardCharsets

object HttpApiSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("HttpApi")(
      test("POST, GET, and HEAD expose a round-trippable immutable blob") {
        val text = "http round trip"
        for
          api          <- makeApi
          upload       <- call(api, Method.POST, "/api/blobs", Body.fromString(text))
          uploadBody   <- upload.body.asString
          blobId       <- ZIO.fromEither(uploadBody.fromJson[BlobId]).mapError(new IllegalArgumentException(_))
          downloaded   <- call(api, Method.GET, s"/api/blobs/${blobId.value}")
          downloadBody <- downloaded.body.asString
          head         <- call(api, Method.HEAD, s"/api/blobs/${blobId.value}")
          headBody     <- head.body.asString
        yield assertTrue(
          upload.status == Status.Created,
          upload.headers.get("Location").contains(s"/api/blobs/${blobId.value}"),
          downloaded.status == Status.Ok,
          downloaded.headers.get("Content-Length").contains(text.getBytes(StandardCharsets.UTF_8).length.toString),
          downloaded.headers.get("ETag").exists(_.nonEmpty),
          downloadBody == text,
          head.status == Status.Ok,
          headBody.isEmpty,
        )
      },
      test("invalid IDs return a structured 400 response") {
        for
          api      <- makeApi
          response <- call(api, Method.GET, "/api/blobs/not-a-content-key")
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.BadRequest,
          body.contains("invalid_blob_id"),
          body.contains("<algorithm>:<hex-digest>:<byte-length>"),
        )
      },
      test("unknown valid IDs return 404 before streaming") {
        val missing = s"sha-256:${"a" * 64}:12"
        for
          api      <- makeApi
          response <- call(api, Method.GET, s"/api/blobs/$missing")
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.NotFound,
          body.contains("blob_not_found"),
        )
      },
      test("DELETE removes a manifest and subsequent GET returns 404") {
        for
          api        <- makeApi
          upload     <- call(api, Method.POST, "/api/blobs", Body.fromString("delete over http"))
          uploadBody <- upload.body.asString
          blobId     <- ZIO.fromEither(uploadBody.fromJson[BlobId]).mapError(new IllegalArgumentException(_))
          deleted    <- call(api, Method.DELETE, s"/api/blobs/${blobId.value}")
          missing    <- call(api, Method.GET, s"/api/blobs/${blobId.value}")
        yield assertTrue(
          deleted.status == Status.NoContent,
          missing.status == Status.NotFound,
        )
      },
      test("empty uploads are rejected as bad requests") {
        for
          api      <- makeApi
          response <- call(api, Method.POST, "/api/blobs")
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.BadRequest,
          body.contains("invalid_blob"),
        )
      },
    )

  private def makeApi: UIO[HttpApi] =
    for graviton <- Graviton.inMemory(chunkSize = 64)
    yield HttpApi(graviton.blobStore, dashboard)

  private def call(
    api: HttpApi,
    method: Method,
    path: String,
    body: Body = Body.empty,
  ): Task[Response] =
    for
      url      <- ZIO.fromEither(URL.decode(s"http://localhost$path"))
      response <- ZIO.scoped(api.app(Request(method = method, url = url, body = body)))
    yield response

  private val dashboard: DatalakeDashboardService = new DatalakeDashboardService:
    override def snapshot = ZIO.succeed(DashboardSamples.snapshot)

    override def metaschema = ZIO.succeed(DashboardSamples.metaschema)

    override def explorer: UIO[SchemaExplorer.Graph] =
      ZIO.succeed(DashboardSamples.schemaExplorer)

    override def updates = ZStream.empty

    override def publish(update: DatalakeDashboard) = ZIO.unit
