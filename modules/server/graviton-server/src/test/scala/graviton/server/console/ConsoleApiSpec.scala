package graviton.server.console

import graviton.protocol.http.BlobIngest
import graviton.server.RuntimeHealth
import graviton.runtime.Graviton
import graviton.runtime.catalog.{Catalog, InMemoryCatalog}
import graviton.shared.ApiJson
import graviton.streams.Chunker
import graviton.core.types.UploadChunkSize
import zio.*
import zio.http.*
import zio.stream.ZStream
import zio.test.*

import java.nio.charset.StandardCharsets

object ConsoleApiSpec extends ZIOSpecDefault:
  override def spec: Spec[TestEnvironment, Any] =
    suite("ConsoleApi")(
      test("raw request bodies stream into CAS and repeated content deduplicates") {
        val bytes = Chunk.fromArray(("stream-me-through-cas-" * 20).getBytes(StandardCharsets.UTF_8))
        for
          graviton       <- Graviton.inMemory(chunkSize = 32)
          catalog        <- ZIO.scoped(InMemoryCatalog.layer.build.map(_.get[Catalog]))
          api             = ConsoleApi(catalog, graviton.blobStore, BlobIngest.make(graviton.blobStore, None), None, testHealth, "test")
          first          <- call(api, "first.txt", bytes)
          firstBody      <- first.body.asString
          firstResult    <- ZIO.fromEither(ApiJson.decode[ConsoleApi.UploadResponse](firstBody)).mapError(new IllegalArgumentException(_))
          second         <- call(api, "second.txt", bytes)
          secondBody     <- second.body.asString
          secondResult   <- ZIO.fromEither(ApiJson.decode[ConsoleApi.UploadResponse](secondBody)).mapError(new IllegalArgumentException(_))
          repeated       <- call(api, "second.txt", bytes)
          repeatedBody   <- repeated.body.asString
          repeatedResult <- ZIO
                              .fromEither(ApiJson.decode[ConsoleApi.UploadResponse](repeatedBody))
                              .mapError(new IllegalArgumentException(_))
          listing        <- catalog.list(None)
          restored       <- graviton.blobStore.get(listing.files.head.blob).runCollect
        yield assertTrue(
          first.status == Status.Created,
          second.status == Status.Created,
          repeated.status == Status.Ok,
          firstResult.blobId == secondResult.blobId,
          firstResult.freshBlocks > 0,
          secondResult.freshBlocks == 0,
          secondResult.duplicateBlocks == secondResult.blockCount,
          !repeatedResult.referenceCreated,
          repeatedResult.fileId == secondResult.fileId,
          listing.files.length == 2,
          restored == bytes,
        )
      },
      test("serves an actionable mobile viewport and DataStar workspace") {
        for
          graviton <- Graviton.inMemory()
          catalog  <- ZIO.scoped(InMemoryCatalog.layer.build.map(_.get[Catalog]))
          api       = ConsoleApi(catalog, graviton.blobStore, BlobIngest.make(graviton.blobStore, None), None, testHealth, "test")
          url      <- ZIO.fromEither(URL.decode("http://localhost/console"))
          response <- ZIO.scoped(api.app(Request.get(url)))
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("name=\"viewport\""),
          body.contains("/console/assets/datastar-v1.0.2.js"),
          body.contains("/console/assets/graviton-logo.svg"),
          body.contains("id=\"workspace\""),
          body.contains("id=\"transfer-rail\""),
          body.contains("type=\"file\""),
          !body.contains("target.innerHTML"),
          !body.contains("cdn.jsdelivr.net"),
        )
      },
      test("renders console actions through the ZIO Blocks Datastar algebra") {
        val click  = ConsoleDatastar.click("@get('/console/library')")
        val link   = ConsoleDatastar.clickPrevent("@get('/console/library')")
        val submit = ConsoleDatastar.submit("@post('/console/folders')")
        val poll   = ConsoleDatastar.interval(5000L, "@get('/console/runtime/panel')")
        assertTrue(
          click == "data-on:click=\"@get(&#x27;/console/library&#x27;)\"",
          link == "data-on:click__prevent=\"@get(&#x27;/console/library&#x27;)\"",
          submit == "data-on:submit__prevent=\"@post(&#x27;/console/folders&#x27;)\"",
          poll == "data-on-interval__duration.5000ms=\"@get(&#x27;/console/runtime/panel&#x27;)\"",
        )
      },
      test("renders real runtime health as a polling DataStar fragment") {
        for
          graviton <- Graviton.inMemory()
          catalog  <- ZIO.scoped(InMemoryCatalog.layer.build.map(_.get[Catalog]))
          api       = ConsoleApi(catalog, graviton.blobStore, BlobIngest.make(graviton.blobStore, None), None, testHealth, "test")
          url      <- ZIO.fromEither(URL.decode("http://localhost/console/runtime"))
          response <- ZIO.scoped(api.app(Request.get(url)))
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("Runtime ready"),
          body.contains("data-on-interval__duration.5000ms"),
          body.contains("Since start"),
          body.contains("href=\"/metrics\""),
          body.contains("/console/assets/graviton-logo.svg"),
          !body.contains("Stream the evidence"),
        )
      },
      test("rejects cross-origin console requests and serves the bundled DataStar runtime") {
        for
          graviton <- Graviton.inMemory()
          catalog  <- ZIO.scoped(InMemoryCatalog.layer.build.map(_.get[Catalog]))
          api       = ConsoleApi(catalog, graviton.blobStore, BlobIngest.make(graviton.blobStore, None), None, testHealth, "test")
          pageUrl  <- ZIO.fromEither(URL.decode("http://localhost/console"))
          assetUrl <- ZIO.fromEither(URL.decode("http://localhost/console/assets/datastar-v1.0.2.js"))
          rejected <- ZIO.scoped(
                        api.app(
                          Request
                            .get(pageUrl)
                            .addHeader(Header.Custom("Host", "localhost"))
                            .addHeader(Header.Custom("Origin", "https://attacker.example"))
                            .addHeader(Header.Custom("Sec-Fetch-Site", "cross-site"))
                        )
                      )
          asset    <- ZIO.scoped(api.app(Request.get(assetUrl)))
          script   <- asset.body.asString
        yield assertTrue(
          rejected.status == Status.Forbidden,
          rejected.headers.get("Access-Control-Allow-Origin").isEmpty,
          asset.status == Status.Ok,
          asset.headers.get("Content-Type").contains("text/javascript; charset=utf-8"),
          script.length > 30000,
          script.startsWith("// Datastar v1.0.2"),
        )
      },
      test("returns folder mutation failures as an accessible workspace fragment") {
        for
          graviton <- Graviton.inMemory()
          catalog  <- ZIO.scoped(InMemoryCatalog.layer.build.map(_.get[Catalog]))
          api       = ConsoleApi(catalog, graviton.blobStore, BlobIngest.make(graviton.blobStore, None), None, testHealth, "test")
          url      <- ZIO.fromEither(
                        URL.decode(
                          "http://localhost/console/folders?name=Research&session=ab573594-abaa-44fa-867a-8c733bf87f6c"
                        )
                      )
          _        <- ZIO.scoped(api.app(Request(method = Method.POST, url = url)))
          conflict <- ZIO.scoped(api.app(Request(method = Method.POST, url = url)))
          body     <- conflict.body.asString
        yield assertTrue(
          conflict.status == Status.Ok,
          conflict.headers.get("Content-Type").contains("text/html; charset=utf-8"),
          body.contains("id=\"workspace\""),
          body.contains("role=\"alert\""),
          body.contains("An entry named &#39;Research&#39; already exists"),
        )
      },
    ) @@ TestAspect.withLiveClock

  private def call(api: ConsoleApi, name: String, bytes: Chunk[Byte]): Task[Response] =
    for
      url      <- ZIO.fromEither(URL.decode(s"http://localhost/console/api/uploads?name=$name&session=ab573594-abaa-44fa-867a-8c733bf87f6c"))
      body      = Body.fromStream(ZStream.fromChunks(bytes.take(17), bytes.drop(17)), bytes.length.toLong)
      request   = Request(
                    method = Method.POST,
                    url = url,
                    headers = Headers(
                      Header.Custom("Content-Type", "text/plain; charset=utf-8"),
                      Header.Custom("Content-Length", bytes.length.toString),
                    ),
                    body = body,
                  )
      response <- Chunker.locally(Chunker.fixed(UploadChunkSize.applyUnsafe(32)))(ZIO.scoped(api.app(request)))
    yield response

  private val testHealth: RuntimeHealth =
    new RuntimeHealth:
      override val refresh: UIO[RuntimeHealth.Snapshot] =
        ZIO.succeed(
          RuntimeHealth.Snapshot(
            storage = RuntimeHealth.StorageStatus.Ready,
            shardcake = None,
            process = RuntimeHealth.ProcessMetrics(0L, 0L, 0L, 0L, 0L, 0L, 0L),
            checkedAtMillis = 0L,
          )
        )
