package graviton.protocol.http

import graviton.runtime.Graviton
import graviton.shared.ApiModels.*
import zio.*
import zio.http.*
import zio.json.*
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
          uploadResult <- ZIO.fromEither(uploadBody.fromJson[BlobUploadResult]).mapError(new IllegalArgumentException(_))
          blobId        = uploadResult.blob.id
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
      test("inventory, manifest inspection, and server verification report persisted bytes") {
        val text = "inspect and verify over http"
        for
          api              <- makeApi
          upload           <- call(api, Method.POST, "/api/blobs", Body.fromString(text))
          uploadBody       <- upload.body.asString
          uploadResult     <- ZIO.fromEither(uploadBody.fromJson[BlobUploadResult]).mapError(new IllegalArgumentException(_))
          blobId            = uploadResult.blob.id
          encodedId         = blobId.value.replace(":", "%3A")
          inventory        <- call(api, Method.GET, "/api/blobs")
          inventoryBody    <- inventory.body.asString
          inventoryResult  <- ZIO.fromEither(inventoryBody.fromJson[BlobListResponse]).mapError(new IllegalArgumentException(_))
          metadata         <- call(api, Method.GET, s"/api/blobs/$encodedId/metadata")
          metadataBody     <- metadata.body.asString
          details          <- ZIO.fromEither(metadataBody.fromJson[BlobDetails]).mapError(new IllegalArgumentException(_))
          verification     <- call(api, Method.POST, s"/api/blobs/$encodedId/verify")
          verificationBody <- verification.body.asString
          verified         <- ZIO
                                .fromEither(verificationBody.fromJson[BlobVerificationResult])
                                .mapError(new IllegalArgumentException(_))
        yield assertTrue(
          inventory.status == Status.Ok,
          inventoryResult.blobs.map(_.id).contains(blobId),
          details.summary.id == blobId,
          details.summary.size.value == text.getBytes(StandardCharsets.UTF_8).length.toLong,
          details.blocks.nonEmpty,
          details.blocks.map(_.size.value).sum == details.summary.size.value,
          verification.status == Status.Ok,
          verified.verified,
          verified.bytesChecked == details.summary.size,
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
          result     <- ZIO.fromEither(uploadBody.fromJson[BlobUploadResult]).mapError(new IllegalArgumentException(_))
          blobId      = result.blob.id
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
    yield HttpApi(graviton.blobStore)

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
