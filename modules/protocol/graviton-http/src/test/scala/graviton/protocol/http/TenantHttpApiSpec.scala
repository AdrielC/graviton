package graviton.protocol.http

import graviton.runtime.Graviton
import graviton.runtime.tenant.{TenantContext, TenantRoute, TenantStoreBinding, TenantStoreProvider}
import graviton.runtime.upload.TenantId
import graviton.security.*
import graviton.shared.ApiModels.BlobUploadResult
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object TenantHttpApiSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("TenantHttpApi")(
    test("binds each authenticated organization before a streamed body escapes the handler") {
      ZIO.scoped {
        for
          first             <- Graviton.inMemory(chunkSize = 64)
          second            <- Graviton.inMemory(chunkSize = 64)
          tenantContextEnv  <- TenantContext.live.build
          tenantContext      = tenantContextEnv.get[TenantContext]
          firstCaller        = caller("10000000-0000-4000-8000-000000000001")
          secondCaller       = caller("20000000-0000-4000-8000-000000000002")
          firstTenant        = TenantId.applyUnsafe(firstCaller.orgId.toString)
          secondTenant       = TenantId.applyUnsafe(secondCaller.orgId.toString)
          provider          <- ZIO.fromEither(
                                 TenantStoreProvider.static(
                                   Chunk(
                                     TenantStoreBinding(TenantRoute(firstTenant), first.blobStore),
                                     TenantStoreBinding(TenantRoute(secondTenant), second.blobStore),
                                   )
                                 )
                               )
          config             = SecurityConfig.Default.copy(enabled = true)
          limiter           <- ZIO.service[RateLimiter].provide(ZLayer.succeed(config), RateLimiter.live)
          audit             <- AuditSink.inMemory
          policy             = HttpSecurityPolicy.make(config, CapabilityCheck.tokenOnly, limiter, audit)
          verifier           = tokenVerifier(Map("tenant-a" -> firstCaller, "tenant-b" -> secondCaller))
          tenantApi          = new TenantHttpApi(provider, tenantContext, first.blobStore, security = Some(policy))
          app                = (tenantApi.routes @@ AuthMiddleware.required(verifier, audit, (_, response) => response, false)).toHandler
          uploaded          <- call(app, "tenant-a", Method.POST, "/api/v1/blobs", Body.fromString("organization-private"))
          uploadedJson      <- uploaded.body.asString
          result            <- ZIO.fromEither(uploadedJson.fromJson[BlobUploadResult]).mapError(new IllegalArgumentException(_))
          callerAfterUpload <- CallerContext.current
          firstDownload     <- call(app, "tenant-a", Method.GET, s"/api/v1/blobs/${result.blob.id.value}")
          firstBytes        <- firstDownload.body.asString
          secondDownload    <- call(app, "tenant-b", Method.GET, s"/api/v1/blobs/${result.blob.id.value}")
          secondBody        <- secondDownload.body.asString
          callerAfterRead   <- CallerContext.current
        yield assertTrue(
          uploaded.status == Status.Created,
          callerAfterUpload.isEmpty,
          firstDownload.status == Status.Ok,
          firstBytes == "organization-private",
          secondDownload.status == Status.NotFound,
          secondBody.contains("blob_not_found"),
          callerAfterRead.isEmpty,
        )
      }
    },
    test("rejects an unknown organization before pulling upload bytes") {
      ZIO.scoped {
        for
          fallback         <- Graviton.inMemory(chunkSize = 64)
          tenantContextEnv <- TenantContext.live.build
          tenantContext     = tenantContextEnv.get[TenantContext]
          knownCaller       = caller("30000000-0000-4000-8000-000000000003")
          unknownCaller     = caller("40000000-0000-4000-8000-000000000004")
          knownTenant       = TenantId.applyUnsafe(knownCaller.orgId.toString)
          provider         <- ZIO.fromEither(
                                TenantStoreProvider.static(
                                  Chunk(TenantStoreBinding(TenantRoute(knownTenant), fallback.blobStore))
                                )
                              )
          audit            <- AuditSink.inMemory
          verifier          = tokenVerifier(Map("known" -> knownCaller, "unknown" -> unknownCaller))
          tenantApi         = new TenantHttpApi(provider, tenantContext, fallback.blobStore)
          app               = (tenantApi.routes @@ AuthMiddleware.required(verifier, audit, (_, response) => response, false)).toHandler
          pulled           <- Ref.make(false)
          body              = Body.fromStreamChunked(zio.stream.ZStream.fromZIO(pulled.set(true)).as(1.toByte))
          response         <- call(app, "unknown", Method.POST, "/api/v1/blobs", body)
          consumed         <- pulled.get
        yield assertTrue(response.status == Status.Forbidden, !consumed)
      }
    },
  )

  private def caller(orgId: String): CallerContext =
    CallerContext(
      orgId = UUID.fromString(orgId),
      principalId = UUID.randomUUID(),
      capabilities = CapabilitySet.of(Capability.BlobRead, Capability.BlobWrite, Capability.BlobDelete),
      jti = UUID.randomUUID().toString,
      tokenExpiresAt = Instant.parse("2099-01-01T00:00:00Z"),
      requestId = UUID.randomUUID(),
    )

  private def tokenVerifier(contexts: Map[String, CallerContext]): JwtVerifier =
    new JwtVerifier:
      override def verify(bearerToken: String, requestId: UUID): IO[SecurityError, CallerContext] =
        ZIO
          .fromOption(contexts.get(bearerToken))
          .orElseFail(SecurityError.Unauthenticated("unknown test token"))
          .map(_.copy(requestId = requestId))

  private def call(
    app: Handler[Any, Nothing, Request, Response],
    token: String,
    method: Method,
    path: String,
    body: Body = Body.empty,
  ): Task[Response] =
    for
      url      <- ZIO.fromEither(URL.decode(s"http://localhost$path"))
      request   = Request(method = method, url = url, body = body)
                    .addHeader(Header.Custom("Authorization", s"Bearer $token"))
      response <- ZIO.scoped(app(request))
    yield response
