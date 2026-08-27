package graviton.protocol.http

import graviton.runtime.Graviton
import graviton.runtime.upload.UploadHttpHeaders
import graviton.security.*
import graviton.core.types.FileSize
import zio.*
import zio.http.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object HttpSecurityPolicySpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] = suite("HttpSecurityPolicy")(
    test("denies a valid caller without blob.write") {
      for
        fixture  <- makeFixture(SecurityConfig.Default.copy(enabled = true))
        response <- callAs(
                      fixture.api,
                      context(Capability.BlobRead),
                      Request.post(URL.decode("http://localhost/api/v1/blobs").toOption.get, Body.fromString("data")),
                    )
        events   <- fixture.audit.drain
      yield assertTrue(
        response.status == Status.Forbidden,
        events.exists(_.outcome == AuditEvent.Outcome.Deny),
      )
    },
    test("enforces streaming body limits without trusting Content-Length") {
      val config = SecurityConfig.Default.copy(enabled = true, maxRequestBytes = FileSize.unsafe(3L))
      for
        fixture  <- makeFixture(config)
        response <- callAs(
                      fixture.api,
                      context(Capability.BlobWrite),
                      Request.post(URL.decode("http://localhost/api/v1/blobs").toOption.get, Body.fromString("four")),
                    )
      yield assertTrue(response.status == Status.RequestEntityTooLarge)
    },
    test("rejects an explicit oversized Content-Length before pulling the body") {
      val config = SecurityConfig.Default.copy(enabled = true, maxRequestBytes = FileSize.unsafe(3L))
      for
        fixture  <- makeFixture(config)
        pulled   <- Ref.make(false)
        body      = Body.fromStreamChunked(zio.stream.ZStream.fromZIO(pulled.set(true)).as(1.toByte))
        request   = Request
                      .post(URL.decode("http://localhost/api/v1/blobs").toOption.get, body)
                      .addHeader(Header.Custom("Content-Length", "4"))
        response <- callAs(fixture.api, context(Capability.BlobWrite), request)
        observed <- pulled.get
      yield assertTrue(
        response.status == Status.RequestEntityTooLarge,
        !observed,
      )
    },
    test("rejects browser origins outside the exact allow list") {
      val config = SecurityConfig.Default.copy(enabled = true, corsAllowedOrigins = List("https://console.example"))
      for
        fixture  <- makeFixture(config)
        request   = Request
                      .get(URL.decode("http://localhost/api/v1/blobs").toOption.get)
                      .addHeader(Header.Custom("Origin", "https://evil.example"))
        response <- callAs(fixture.api, context(Capability.BlobRead), request)
      yield assertTrue(response.status == Status.Forbidden)
    },
    test("permits an exact-origin bearer-token preflight without authentication") {
      val config = SecurityConfig.Default.copy(enabled = true, corsAllowedOrigins = List("https://console.example"))
      for
        fixture  <- makeFixture(config)
        request   = Request
                      .options(URL.decode("http://localhost/api/v1/blobs").toOption.get)
                      .addHeader(Header.Custom("Origin", "https://console.example"))
                      .addHeader(Header.Custom("Access-Control-Request-Method", "POST"))
                      .addHeader(
                        Header.Custom(
                          "Access-Control-Request-Headers",
                          s"authorization, content-type, ${UploadHttpHeaders.TenantId}, ${UploadHttpHeaders.UploadSession}",
                        )
                      )
        response <- ZIO.scoped(fixture.api.preflightApp(request))
      yield assertTrue(
        response.status == Status.NoContent,
        response.headers.get("Access-Control-Allow-Origin").contains("https://console.example"),
        response.headers.get("Access-Control-Allow-Headers").exists(_.contains("authorization")),
        response.headers.get("Access-Control-Allow-Headers").exists(_.contains(UploadHttpHeaders.TenantId.toLowerCase)),
        response.headers.get("Access-Control-Allow-Headers").exists(_.contains(UploadHttpHeaders.UploadSession.toLowerCase)),
      )
    },
    test("rejects an upload session for a different tenant before pulling bytes") {
      val caller  = context(Capability.BlobWrite)
      val session = UUID.randomUUID()
      for
        fixture      <- makeFixture(SecurityConfig.Default.copy(enabled = true))
        pulled       <- Ref.make(false)
        body          = Body.fromStreamChunked(zio.stream.ZStream.fromZIO(pulled.set(true)).as(1.toByte))
        request       = Request
                          .post(URL.decode("http://localhost/api/v1/blobs").toOption.get, body)
                          .addHeader(Header.Custom(UploadHttpHeaders.TenantId, UUID.randomUUID().toString))
                          .addHeader(Header.Custom(UploadHttpHeaders.UploadSession, session.toString))
        response     <- callAs(fixture.api, caller, request)
        observed     <- pulled.get
        responseBody <- response.body.asString
      yield assertTrue(
        response.status == Status.Forbidden,
        responseBody.contains("tenant_mismatch"),
        !observed,
      )
    },
    test("rejects disallowed preflight origins, methods, and headers") {
      val config = SecurityConfig.Default.copy(enabled = true, corsAllowedOrigins = List("https://console.example"))
      for
        fixture        <- makeFixture(config)
        badOrigin       = Request
                            .options(URL.decode("http://localhost/api/v1/blobs").toOption.get)
                            .addHeader(Header.Custom("Origin", "https://evil.example"))
                            .addHeader(Header.Custom("Access-Control-Request-Method", "POST"))
        badMethod       = Request
                            .options(URL.decode("http://localhost/api/v1/blobs").toOption.get)
                            .addHeader(Header.Custom("Origin", "https://console.example"))
                            .addHeader(Header.Custom("Access-Control-Request-Method", "DELETE"))
        badHeader       = Request
                            .options(URL.decode("http://localhost/api/v1/blobs").toOption.get)
                            .addHeader(Header.Custom("Origin", "https://console.example"))
                            .addHeader(Header.Custom("Access-Control-Request-Method", "POST"))
                            .addHeader(Header.Custom("Access-Control-Request-Headers", "x-surprise"))
        originResponse <- ZIO.scoped(fixture.api.preflightApp(badOrigin))
        methodResponse <- ZIO.scoped(fixture.api.preflightApp(badMethod))
        headerResponse <- ZIO.scoped(fixture.api.preflightApp(badHeader))
      yield assertTrue(
        originResponse.status == Status.Forbidden,
        methodResponse.status == Status.Forbidden,
        headerResponse.status == Status.Forbidden,
      )
    },
    test("adds an allowed CORS origin to authentication failures") {
      val config = SecurityConfig.Default.copy(enabled = true, corsAllowedOrigins = List("https://console.example"))
      for
        fixture                                             <- makeFixture(config)
        request                                              = Request
                                                                 .get(URL.decode("http://localhost/api/v1/blobs").toOption.get)
                                                                 .addHeader(Header.Custom("Origin", "https://console.example"))
        securedApp: Handler[Any, Nothing, Request, Response] =
          (fixture.api.routes @@ AuthMiddleware.required(JwtVerifier.denyAll, fixture.audit, fixture.policy.addCorsHeaders)).toHandler
        response                                            <- ZIO.scoped(securedApp(request))
      yield assertTrue(
        response.status == Status.Unauthorized,
        response.headers.get("Access-Control-Allow-Origin").contains("https://console.example"),
      )
    },
    test("rate-limits repeated requests for one principal") {
      val config = SecurityConfig.Default.copy(enabled = true, rateLimitPerPrincipalPerSec = 1L)
      val caller = context(Capability.BlobRead)
      for
        fixture <- makeFixture(config)
        request  = Request.get(URL.decode("http://localhost/api/v1/blobs").toOption.get)
        first   <- callAs(fixture.api, caller, request)
        second  <- callAs(fixture.api, caller, request)
      yield assertTrue(first.status == Status.Ok, second.status == Status.TooManyRequests)
    },
  )

  private final case class Fixture(api: HttpApi, audit: AuditSink & AuditSink.Inspect, policy: HttpSecurityPolicy)

  private def makeFixture(config: SecurityConfig): UIO[Fixture] =
    for
      graviton <- Graviton.inMemory(chunkSize = 64)
      limiter  <- ZIO.service[RateLimiter].provide(ZLayer.succeed(config), RateLimiter.live)
      sink     <- AuditSink.inMemory
      policy    = HttpSecurityPolicy.make(config, CapabilityCheck.tokenOnly, limiter, sink)
    yield Fixture(HttpApi(graviton.blobStore, security = Some(policy)), sink, policy)

  private def context(capabilities: Capability*): CallerContext =
    CallerContext(
      orgId = UUID.randomUUID(),
      principalId = UUID.randomUUID(),
      capabilities = CapabilitySet.of(capabilities*),
      jti = UUID.randomUUID().toString,
      tokenExpiresAt = Instant.parse("2099-01-01T00:00:00Z"),
      requestId = UUID.randomUUID(),
    )

  private def callAs(api: HttpApi, caller: CallerContext, request: Request): Task[Response] =
    CallerContext.scopedWith(caller)(ZIO.scoped(api.app(request)))
