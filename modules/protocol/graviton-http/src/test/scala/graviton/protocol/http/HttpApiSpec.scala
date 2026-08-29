package graviton.protocol.http

import graviton.runtime.Graviton
import graviton.runtime.upload.*
import graviton.shared.ApiModels.*
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

import java.nio.charset.StandardCharsets

object HttpApiSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("HttpApi")(
      test("typed tenant and session headers opt into one locality-aware upload") {
        val tenant  = TenantId.applyUnsafe("9f2f172c-8e6b-4aef-8be8-4c750420d971")
        val session = UploadSessionId.applyUnsafe("ab573594-abaa-44fa-867a-8c733bf87f6c")
        val node    = UploadNode.fromEndpoints(
          UploadNodeHost.applyUnsafe("node-a"),
          UploadNodePort.applyUnsafe(54321),
          UploadNodePort.applyUnsafe(54322),
        )
        val headers = Headers(
          Header.Custom("X-Graviton-Tenant-Id", tenant.value),
          Header.Custom("X-Graviton-Upload-Session-Id", session.value),
        )

        for
          graviton <- Graviton.inMemory(chunkSize = 64)
          calls    <- Ref.make(0)
          localized = new LocalityAwareUpload:
                        override def upload(
                          key: UploadSessionKey,
                          intent: UploadIntent,
                          bytes: zio.stream.ZStream[Any, Throwable, Byte],
                        ): IO[LocalityAwareUpload.Error, LocalizedUploadResult] =
                          calls.update(_ + 1) *>
                            bytes
                              .run(graviton.blobStore.put())
                              .map(result => LocalizedUploadResult(result.key, result.stats, node))
                              .mapError(cause => LocalityAwareUpload.Error.LocalIngest(UploadNodeIngest.Error.StorageFailure(cause)))
          api       = HttpApi(graviton.blobStore, localizedUpload = Some(localized))
          response <- call(api, Method.POST, "/api/v1/blobs", Body.fromString("localized"), headers)
          count    <- calls.get
        yield assertTrue(response.status == Status.Created, count == 1)
      },
      test("locality headers fail explicitly when the server has no locality runtime") {
        val headers = Headers(
          Header.Custom(UploadHttpHeaders.TenantId, "9f2f172c-8e6b-4aef-8be8-4c750420d971"),
          Header.Custom(UploadHttpHeaders.UploadSession, "ab573594-abaa-44fa-867a-8c733bf87f6c"),
        )

        for
          api      <- makeApi
          response <- call(api, Method.POST, "/api/v1/blobs", Body.fromString("not silently downgraded"), headers)
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.ServiceUnavailable,
          body.contains("locality_unavailable"),
        )
      },
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
          downloaded.body.knownContentLength.contains(text.getBytes(StandardCharsets.UTF_8).length.toLong),
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
      test("Content-Length overflow and underflow fail before manifest commit") {
        val overflowHeaders  = Headers(Header.Custom("Content-Length", "3"))
        val underflowHeaders = Headers(Header.Custom("Content-Length", "5"))

        for
          api             <- makeApi
          overflow        <- call(api, Method.POST, "/api/v1/blobs", Body.fromString("four"), overflowHeaders)
          overflowBody    <- overflow.body.asString
          afterOverflow   <- call(api, Method.GET, "/api/v1/blobs")
          overflowList    <- afterOverflow.body.asString
          listedOverflow  <- ZIO.fromEither(overflowList.fromJson[BlobListResponse]).mapError(new IllegalArgumentException(_))
          underflow       <- call(api, Method.POST, "/api/v1/blobs", Body.fromString("four"), underflowHeaders)
          underflowBody   <- underflow.body.asString
          afterUnderflow  <- call(api, Method.GET, "/api/v1/blobs")
          underflowList   <- afterUnderflow.body.asString
          listedUnderflow <- ZIO.fromEither(underflowList.fromJson[BlobListResponse]).mapError(new IllegalArgumentException(_))
        yield assertTrue(
          overflow.status == Status.BadRequest,
          overflowBody.contains("expected 3 bytes but received more"),
          listedOverflow.blobs.isEmpty,
          underflow.status == Status.BadRequest,
          underflowBody.contains("expected 5 bytes but received 4"),
          listedUnderflow.blobs.isEmpty,
        )
      },
      test("an out-of-domain Content-Length is rejected before the body is pulled") {
        for
          api      <- makeApi
          pulled   <- Ref.make(false)
          body      = Body.fromStreamChunked(zio.stream.ZStream.fromZIO(pulled.set(true)).as(1.toByte))
          headers   = Headers(Header.Custom("Content-Length", "1099511627777"))
          response <- call(api, Method.POST, "/api/v1/blobs", body, headers)
          observed <- pulled.get
        yield assertTrue(
          response.status == Status.BadRequest,
          !observed,
        )
      },
      test("Content-Length accepts leading zeros but rejects non-ASCII decimal syntax before body pull") {
        val malformed = List("+1", "-1", " 1", "1 ", "1\t", "1e0", "١", "１")

        for
          api      <- makeApi
          accepted <- call(
                        api,
                        Method.POST,
                        "/api/v1/blobs",
                        Body.fromString("four"),
                        Headers(Header.Custom("Content-Length", "0004")),
                      )
          rejected <- ZIO.foreach(malformed) { raw =>
                        for
                          pulled       <- Ref.make(false)
                          body          = Body.fromStreamChunked(zio.stream.ZStream.fromZIO(pulled.set(true)).as(1.toByte))
                          response     <- call(
                                            api,
                                            Method.POST,
                                            "/api/v1/blobs",
                                            body,
                                            Headers(Header.Custom("Content-Length", raw)),
                                          )
                          responseBody <- response.body.asString
                          observed     <- pulled.get
                        yield (response.status, responseBody, observed)
                      }
        yield assertTrue(
          accepted.status == Status.Created,
          rejected.forall { case (status, responseBody, observed) =>
            status == Status.BadRequest && responseBody.contains("Invalid Content-Length") && !observed
          },
        )
      },
      test("application/pdf uploads run through zio-pdf and round-trip") {
        val pdf         =
          "%PDF-1.7\n" +
            "1 0 obj\n<</Type /Catalog>>\nendobj\n" +
            "trailer\n<</Root 1 0 R>>\nstartxref\n0\n%%EOF\n"
        val contentType = Headers(Header.Custom("Content-Type", "application/pdf; profile=archive"))

        for
          api          <- makeApi
          upload       <- call(api, Method.POST, "/api/v1/blobs", Body.fromString(pdf), contentType)
          uploadBody   <- upload.body.asString
          uploadResult <- ZIO.fromEither(uploadBody.fromJson[BlobUploadResult]).mapError(new IllegalArgumentException(_))
          downloaded   <- call(api, Method.GET, s"/api/v1/blobs/${uploadResult.blob.id.value}")
          restored     <- downloaded.body.asString
        yield assertTrue(
          upload.status == Status.Created,
          restored == pdf,
          uploadResult.blob.size.value == pdf.getBytes(StandardCharsets.UTF_8).length.toLong,
        )
      },
      test("byte sniffing selects PDF ingest when Content-Type is omitted") {
        val pdf =
          "%PDF-1.7\n" +
            "1 0 obj\n<</Type /Catalog>>\nendobj\n" +
            "trailer\n<</Root 1 0 R>>\nstartxref\n0\n%%EOF\n"

        for
          api          <- makeApi
          upload       <- call(api, Method.POST, "/api/v1/blobs", Body.fromString(pdf))
          uploadBody   <- upload.body.asString
          uploadResult <- ZIO.fromEither(uploadBody.fromJson[BlobUploadResult]).mapError(new IllegalArgumentException(_))
          downloaded   <- call(api, Method.GET, s"/api/v1/blobs/${uploadResult.blob.id.value}")
          restored     <- downloaded.body.asString
        yield assertTrue(upload.status == Status.Created, restored == pdf)
      },
      test("byte sniffing rejects a concrete MIME claim that disagrees with a PDF") {
        val pdf         = "%PDF-1.7\n1 0 obj\n<</Type /Catalog>>\nendobj\n%%EOF\n"
        val contentType = Headers(Header.Custom("Content-Type", "text/plain"))

        for
          api      <- makeApi
          response <- call(api, Method.POST, "/api/v1/blobs", Body.fromString(pdf), contentType)
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.BadRequest,
          body.contains("advertised text/plain does not match detected application/pdf"),
        )
      },
      test("application/pdf rejects a mismatched byte signature") {
        val contentType = Headers(Header.Custom("Content-Type", "application/pdf"))

        for
          api      <- makeApi
          response <- call(api, Method.POST, "/api/v1/blobs", Body.fromString("this is not a PDF"), contentType)
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.BadRequest,
          body.contains("invalid_blob"),
          body.contains("%PDF-"),
        )
      },
      test("malformed Content-Type is rejected before upload") {
        val contentType = Headers(Header.Custom("Content-Type", "application/pdf; charset"))

        for
          api      <- makeApi
          response <- call(api, Method.POST, "/api/v1/blobs", Body.fromString("bytes"), contentType)
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.BadRequest,
          body.contains("Invalid Content-Type"),
        )
      },
      test("v1 supports byte ranges and conditional reads") {
        val text = "0123456789abcdefghij"
        for
          api        <- makeApi
          upload     <- call(api, Method.POST, "/api/v1/blobs", Body.fromString(text))
          uploadBody <- upload.body.asString
          result     <- ZIO.fromEither(uploadBody.fromJson[BlobUploadResult]).mapError(new IllegalArgumentException(_))
          path        = s"/api/v1/blobs/${result.blob.id.value}"
          etag       <- ZIO.fromOption(upload.headers.get("ETag")).orElseFail(new IllegalStateException("missing ETag"))
          head       <- call(api, Method.HEAD, path)
          modified   <- ZIO.fromOption(head.headers.get("Last-Modified")).orElseFail(new IllegalStateException("missing Last-Modified"))
          ranged     <- call(api, Method.GET, path, headers = Headers(Header.Custom("Range", "bytes=3-7")))
          rangeBody  <- ranged.body.asString
          suffix     <- call(api, Method.GET, path, headers = Headers(Header.Custom("Range", "bytes=-4")))
          suffixBody <- suffix.body.asString
          cached     <- call(api, Method.GET, path, headers = Headers(Header.Custom("If-None-Match", etag)))
          cachedBody <- cached.body.asString
          dateCached <- call(api, Method.GET, path, headers = Headers(Header.Custom("If-Modified-Since", modified)))
          rejected   <- call(api, Method.GET, path, headers = Headers(Header.Custom("Range", "bytes=99-100")))
        yield assertTrue(
          upload.status == Status.Created,
          upload.headers.get("Location").contains(path),
          upload.headers.get("Deprecation").isEmpty,
          ranged.status == Status.PartialContent,
          ranged.headers.get("Content-Range").contains(s"bytes 3-7/${text.length}"),
          ranged.body.knownContentLength.contains(5L),
          rangeBody == "34567",
          suffix.status == Status.PartialContent,
          suffix.body.knownContentLength.contains(4L),
          suffixBody == "ghij",
          cached.status == Status.NotModified,
          cached.headers.get("Content-Length").isEmpty,
          cachedBody.isEmpty,
          dateCached.status == Status.NotModified,
          rejected.status == Status.RequestedRangeNotSatisfiable,
          rejected.headers.get("Content-Range").contains(s"bytes */${text.length}"),
        )
      },
      test("cursor pagination is stable and legacy routes advertise their successor") {
        for
          api        <- makeApi
          _          <-
            ZIO.foreachDiscard(List("page-a", "page-b", "page-c"))(value => call(api, Method.POST, "/api/v1/blobs", Body.fromString(value)))
          first      <- call(api, Method.GET, "/api/v1/blobs?limit=1")
          firstBody  <- first.body.asString
          firstPage  <- ZIO.fromEither(firstBody.fromJson[BlobListResponse]).mapError(new IllegalArgumentException(_))
          cursor     <- ZIO.fromOption(firstPage.nextCursor).orElseFail(new IllegalStateException("missing next cursor"))
          second     <- call(api, Method.GET, s"/api/v1/blobs?limit=1&cursor=$cursor")
          secondBody <- second.body.asString
          secondPage <- ZIO.fromEither(secondBody.fromJson[BlobListResponse]).mapError(new IllegalArgumentException(_))
          legacy     <- call(api, Method.GET, "/api/blobs?limit=1")
          invalid    <- call(api, Method.GET, "/api/v1/blobs?limit=0")
          badCursor  <- call(api, Method.GET, "/api/v1/blobs?cursor=missing")
        yield assertTrue(
          first.status == Status.Ok,
          firstPage.blobs.size == 1,
          second.status == Status.Ok,
          secondPage.blobs.size == 1,
          secondPage.blobs.head.id != firstPage.blobs.head.id,
          legacy.headers.get("Deprecation").contains("true"),
          legacy.headers.get("Link").exists(_.contains("/api/v1/blobs")),
          invalid.status == Status.BadRequest,
          badCursor.status == Status.BadRequest,
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
    headers: Headers = Headers.empty,
  ): Task[Response] =
    for
      url      <- ZIO.fromEither(URL.decode(s"http://localhost$path"))
      response <- ZIO.scoped(api.app(Request(method = method, url = url, headers = headers, body = body)))
    yield response
