package graviton.server

import graviton.backend.pg.{PgBlobManifestRepo, PgDataSource, PgMaintenanceCoordinator}
import graviton.backend.s3.S3BlockStore
import graviton.protocol.http.{AuthMiddleware, DevAuthRoutes, HttpApi, HttpSecurityPolicy, MetricsHttpApi}
import graviton.protocol.grpc.{AuthInterceptor, CapabilityInterceptor, GravitonGrpcServer, GrpcServerConfig, RateLimitInterceptor}
import graviton.runtime.config.{GravitonConfig, MaintenanceConfig}
import graviton.runtime.metrics.{InMemoryMetricsRegistry, MetricsRegistry}
import graviton.runtime.stores.{
  BlobManifestRepo,
  BlobStore,
  BlockStore,
  CasBlobStore,
  FileMaintenanceCoordinator,
  FsBlobManifestRepo,
  FsBlockStore,
  MaintenanceCoordinator,
}
import graviton.core.types.UploadChunkSize
import graviton.streams.Chunker
import graviton.security.*
import graviton.security.jwt.{HmacJwtVerifier, OidcJwtVerifier}
import graviton.shared.ApiModels.*
import graviton.shared.ApiJson
import zio.*
import zio.http.*
import zio.json.EncoderOps
import zio.json.ast.Json

import java.nio.file.Path
import java.util.concurrent.TimeUnit

object Main extends ZIOAppDefault:

  override def run: ZIO[Any, Any, Any] =
    for
      cfg                                          <- ZIO.config(GravitonConfig.config)
      maintenance                                  <- ZIO.config(MaintenanceConfig.config)
      sec                                          <- ZIO.config(SecurityConfig.config)
      _                                            <- validateSecurityOrFail(sec)
      _                                            <- logSecurityPosture(sec)
      port                                          = cfg.httpPort
      started                                      <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _                                            <- ZIO.logInfo(s"Starting Graviton server on :$port")
      verifierOpt                                  <- buildVerifier(sec)
      program                                       = ZIO.scoped {
                                                        for
                                                          blobStore       <- ZIO.service[BlobStore]
                                                          auditSink       <- ZIO.service[AuditSink]
                                                          capabilityCheck <- ZIO.service[CapabilityCheck]
                                                          rateLimiter     <- ZIO.service[RateLimiter]
                                                          runtime         <- ZIO.runtime[Any]
                                                          interceptors     = verifierOpt.toList.flatMap { verifier =>
                                                                               List(
                                                                                 new AuthInterceptor(verifier, auditSink, runtime),
                                                                                 new CapabilityInterceptor(capabilityCheck, runtime, Some(auditSink)),
                                                                                 new RateLimitInterceptor(rateLimiter, runtime),
                                                                               )
                                                                             }
                                                          grpc            <- GravitonGrpcServer.scoped(
                                                                               blobStore,
                                                                               GrpcServerConfig(cfg.grpcPort),
                                                                               interceptors,
                                                                             )
                                                          boundPort       <- grpc.port
                                                          _               <- ZIO.logInfo(s"gRPC API listening on :$boundPort")
                                                          routes          <- ZIO.serviceWithZIO[BlobStore] { blobStore =>
                                                                               ZIO.serviceWithZIO[MetricsRegistry] { metrics =>
                                                                                 ZIO.serviceWithZIO[AuditSink] { auditSink =>
                                                                                   ZIO.serviceWithZIO[CapabilityCheck] { capabilityCheck =>
                                                                                     ZIO.serviceWithZIO[RateLimiter] { rateLimiter =>
                                                                                       val policy = Option.when(sec.enabled)(
                                                                                         HttpSecurityPolicy.make(sec, capabilityCheck, rateLimiter, auditSink)
                                                                                       )
                                                                                       val api    = HttpApi(
                                                                                         blobStore = blobStore,
                                                                                         metrics = Some(MetricsHttpApi(metrics, policy)),
                                                                                         security = policy,
                                                                                       )

                                                                                       val publicRoutes: Routes[Any, Nothing] =
                                                                                         Routes(
                                                                                           Method.GET / "api" / "health"           -> Handler.fromZIO {
                                                                                             for
                                                                                               now <- Clock.currentTime(TimeUnit.MILLISECONDS)
                                                                                               up   = (now - started).max(0L)
                                                                                             yield Response.json(
                                                                                               ApiJson.encode(
                                                                                                 HealthResponse(
                                                                                                   status = "ok",
                                                                                                   version = _root_.graviton.server.BuildInfo.version,
                                                                                                   uptime = up,
                                                                                                 )
                                                                                               )
                                                                                             )
                                                                                           },
                                                                                           Method.GET / "api" / "health" / "live"  -> Handler.fromZIO {
                                                                                             for
                                                                                               now <- Clock.currentTime(TimeUnit.MILLISECONDS)
                                                                                               up   = (now - started).max(0L)
                                                                                             yield Response.json(
                                                                                               ApiJson.encode(
                                                                                                 HealthResponse(
                                                                                                   status = "ok",
                                                                                                   version = _root_.graviton.server.BuildInfo.version,
                                                                                                   uptime = up,
                                                                                                 )
                                                                                               )
                                                                                             )
                                                                                           },
                                                                                           Method.GET / "api" / "health" / "ready" -> Handler.fromZIO {
                                                                                             blobStore.healthCheck.timeout(5.seconds).either.map {
                                                                                               case Right(Some(_)) =>
                                                                                                 Response.json(
                                                                                                   Json
                                                                                                     .Obj(
                                                                                                       "status"  -> Json.Str("ready"),
                                                                                                       "version" -> Json.Str(_root_.graviton.server.BuildInfo.version),
                                                                                                     )
                                                                                                     .toJson
                                                                                                 )
                                                                                               case _              =>
                                                                                                 Response
                                                                                                   .json(Json.Obj("status" -> Json.Str("not_ready")).toJson)
                                                                                                   .copy(status = Status.ServiceUnavailable)
                                                                                             }
                                                                                           },
                                                                                         )

                                                                                       val statsRoutes: Routes[Any, Nothing] = Routes(
                                                                                         Method.GET / "api" / "stats" -> Handler.fromFunctionZIO[Request] { request =>
                                                                                           val resource = ResourceRef(ResourceKind.Namespace, None)
                                                                                           val response =
                                                                                             metrics.snapshot.map(snapshot => Response.json(ApiJson.encode(RuntimeStats.from(snapshot))))
                                                                                           policy match
                                                                                             case None         => response
                                                                                             case Some(active) =>
                                                                                               active
                                                                                                 .authorize(request, "observability.stats.read", Capability.ObservabilityRead, resource)
                                                                                                 .foldZIO(
                                                                                                   denied => ZIO.succeed(denied),
                                                                                                   _ =>
                                                                                                     response.flatMap(result =>
                                                                                                       active.recordOutcome("observability.stats.read", resource, result).as(result)
                                                                                                     ),
                                                                                                 )
                                                                                         }
                                                                                       )

                                                                                       val appRoutes: Routes[Any, Nothing] =
                                                                                         verifierOpt match
                                                                                           case Some(verifier) =>
                                                                                             val decorateFailure =
                                                                                               (request: Request, response: Response) =>
                                                                                                 policy.fold(response)(_.addCorsHeaders(request, response))
                                                                                             (api.routes ++ statsRoutes) @@ AuthMiddleware.required(verifier, auditSink, decorateFailure)
                                                                                           case None           =>
                                                                                             api.routes ++ statsRoutes

                                                                                       val devRoutes: Routes[Any, Nothing] =
                                                                                         sec.devSharedSecret match
                                                                                           case Some(secret) if sec.enabled =>
                                                                                             DevAuthRoutes.routes(secret, sec.oidcIssuer, sec.oidcAudience)
                                                                                           case _                           =>
                                                                                             Routes.empty

                                                                                       val preflightRoutes = if sec.enabled then api.preflightRoutes else Routes.empty
                                                                                       val baseRoutes      = publicRoutes ++ preflightRoutes ++ appRoutes ++ devRoutes
                                                                                       val routes          = if sec.enabled then baseRoutes else Middleware.cors(baseRoutes)

                                                                                       ZIO.succeed(routes)
                                                                                     }
                                                                                   }
                                                                                 }
                                                                               }
                                                                             }
                                                          chunker          = Chunker.fixed(UploadChunkSize.applyUnsafe(cfg.chunkSize))
                                                          _               <- Chunker.locally(chunker)(Server.serve(routes))
                                                        yield ()
                                                      }

      auditLayer: ZLayer[Any, Throwable, AuditSink] = sec.auditBackend match
                                                        case "jdbc"   =>
                                                          PgDataSource.layerFromEnv >>> AuditSink.jdbc
                                                        case "memory" =>
                                                          ZLayer.fromZIO[Any, Nothing, AuditSink](AuditSink.inMemory)
                                                        case other    =>
                                                          ZLayer.fail(
                                                            new IllegalArgumentException(
                                                              s"Unsupported GRAVITON_SECURITY_AUDIT_BACKEND='$other' (expected 'jdbc' or 'memory')"
                                                            )
                                                          )

      _ <- program.provide(
             Server.defaultWith(_.port(port).enableRequestStreaming),
             blobLayer(cfg, maintenance),
             auditLayer,
             capabilityLayer(sec),
             ZLayer.succeed(sec) >>> RateLimiter.live,
             InMemoryMetricsRegistry.layer,
           )
    yield ()

  private[server] def blobLayer(
    cfg: GravitonConfig,
    maintenance: MaintenanceConfig,
  ): ZLayer[MetricsRegistry, Throwable, BlobStore] =
    val storageLayer =
      cfg.blobBackend.toLowerCase match
        case "s3" | "minio" =>
          ZLayer.make[BlockStore & BlobManifestRepo & MaintenanceCoordinator](
            PgDataSource.layerFromEnv,
            PgBlobManifestRepo.layer,
            PgMaintenanceCoordinator.layer(maintenance),
            S3BlockStore.layerFromEnv,
          )
        case "fs"           =>
          val root   = Path.of(cfg.fs.root)
          val prefix = cfg.fs.blockPrefix
          ZLayer.make[BlockStore & BlobManifestRepo & MaintenanceCoordinator](
            ZLayer.succeed[BlockStore](new FsBlockStore(root, prefix)),
            ZLayer.succeed[BlobManifestRepo](new FsBlobManifestRepo(root)),
            FileMaintenanceCoordinator.layer(root, maintenance),
          )
        case other          =>
          ZLayer.fail(
            new IllegalArgumentException(
              s"Unsupported GRAVITON_BLOB_BACKEND='$other' (expected 's3', 'minio', or 'fs')"
            )
          )

    (storageLayer ++ ZLayer.service[MetricsRegistry]) >>> CasBlobStore.coordinatedLayerWithMetrics

  /** Compatibility entrypoint for embedded tests and callers using defaults. */
  private[server] def blobLayer(cfg: GravitonConfig): ZLayer[MetricsRegistry, Throwable, BlobStore] =
    blobLayer(cfg, MaintenanceConfig.Default)

  private def validateSecurityOrFail(sec: SecurityConfig): Task[Unit] =
    ZIO
      .fromEither(sec.validate)
      .mapError(msg => new IllegalStateException(s"invalid GRAVITON_SECURITY_* config: $msg"))
      .unit

  private def capabilityLayer(sec: SecurityConfig): ZLayer[Any, Throwable, CapabilityCheck] =
    sec.authorizationBackend match
      case "token" => ZLayer.succeed(CapabilityCheck.tokenOnly)
      case "jdbc"  => PgDataSource.layerFromEnv >>> CapabilityCheck.jdbc
      case other   => ZLayer.fail(new IllegalArgumentException(s"Unsupported authorization backend: $other"))

  private def logSecurityPosture(sec: SecurityConfig): UIO[Unit] =
    if sec.enabled then
      val mode = sec.devSharedSecret match
        case Some(_) => "HS256 dev shared-secret"
        case None    => "OIDC RS256 with remote JWKS rotation"
      ZIO.logInfo(
        s"Security: ENABLED | mode=$mode audit=${sec.auditBackend} " +
          s"issuer=${sec.oidcIssuer.getOrElse("?")} audience=${sec.oidcAudience.getOrElse("?")} " +
          s"tls=${sec.requireTls} rate=${sec.rateLimitPerPrincipalPerSec}/s"
      )
    else
      ZIO.logWarning(
        s"Security: DISABLED (GRAVITON_SECURITY_ENABLED=false, audit=${sec.auditBackend}). " +
          "API is open; do not expose this listener to untrusted networks."
      )

  /**
   * Build the active JWT verifier.
   *
   * - Security disabled: no verifier, middleware is not applied.
   * - Security enabled + dev shared secret: HS256 verifier and `/dev/token`
   *   mint endpoint are available so local clients can curl the API.
   * - Security enabled + no dev secret: RS256 OIDC verification is built
   *   from the configured HTTPS JWKS URI.
   */
  private def buildVerifier(sec: SecurityConfig): Task[Option[JwtVerifier]] =
    if !sec.enabled then ZIO.none
    else
      sec.devSharedSecret match
        case Some(secret) =>
          ZIO.some(HmacJwtVerifier.make(secret, sec.clockSkewSeconds, sec.oidcIssuer, sec.oidcAudience))
        case None         =>
          OidcJwtVerifier.make(sec).map(Some(_))
