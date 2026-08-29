package graviton.server

import graviton.backend.pg.{PgBlobManifestRepo, PgCatalog, PgDataSource, PgMaintenanceCoordinator}
import graviton.backend.s3.S3BlockStore
import graviton.integration.shardcake.{ShardcakeNode, ShardcakeUploadConfig}
import graviton.pdf.PdfUploadSupport
import graviton.protocol.http.{AuthMiddleware, BlobIngest, DevAuthRoutes, HttpApi, HttpSecurityPolicy, MetricsHttpApi}
import graviton.protocol.grpc.{AuthInterceptor, CapabilityInterceptor, GravitonGrpcServer, GrpcServerConfig, RateLimitInterceptor}
import graviton.runtime.catalog.{Catalog, FsCatalog}
import graviton.runtime.config.{BlockPersistenceConfig, GravitonConfig, MaintenanceConfig}
import graviton.runtime.metrics.MetricsRegistry
import graviton.runtime.stores.{
  BlobManifestRepo,
  BlobStore,
  BlockStore,
  CasBlobStore,
  FileMaintenanceCoordinator,
  FsBlobManifestRepo,
  FsBlockStore,
  MaintenanceCoordinator,
  MetricsBlobStore,
}
import graviton.core.types.UploadChunkSize
import graviton.streams.Chunker
import graviton.security.*
import graviton.security.jwt.{HmacJwtVerifier, OidcJwtVerifier}
import graviton.shared.ApiModels.*
import graviton.shared.ApiJson
import graviton.server.console.{ConsoleApi, ConsoleConfig}
import graviton.server.metrics.ZioMetricsRegistry
import zio.*
import zio.http.*
import zio.metrics.connectors.MetricsConfig
import zio.metrics.connectors.prometheus
import zio.metrics.jvm.DefaultJvmMetrics
import zio.json.EncoderOps
import zio.json.ast.Json

import java.nio.file.Path
import java.util.concurrent.TimeUnit

object Main extends ZIOAppDefault:

  override def run: ZIO[Any, Any, Any] =
    for
      cfg                                          <- ZIO.config(GravitonConfig.config)
      maintenance                                  <- ZIO.config(MaintenanceConfig.config)
      blockPersistence                             <- ZIO.config(BlockPersistenceConfig.config)
      shardcake                                    <- ZIO.config(ShardcakeUploadConfig.config)
      console                                      <- ZIO.config(ConsoleConfig.config)
      healthConfig                                 <- ZIO.config(RuntimeHealth.Config.config)
      sec                                          <- ZIO.config(SecurityConfig.config)
      _                                            <- validateSecurityOrFail(sec)
      _                                            <- validateShardcakeTopology(cfg, shardcake)
      _                                            <- validateConsoleSecurity(sec, console)
      _                                            <- logSecurityPosture(sec)
      port                                          = cfg.httpPort
      started                                      <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _                                            <- ZIO.logInfo(s"Starting Graviton server on :$port")
      verifierOpt                                  <- buildVerifier(sec)
      program                                       = ZIO.scoped {
                                                        for
                                                          _                                  <- DefaultJvmMetrics.liveV2.build.unit
                                                          blobStore                          <- ZIO.service[BlobStore]
                                                          catalog                            <- ZIO.service[Catalog]
                                                          shardcakeNode                      <- ZIO.service[Option[ShardcakeNode]]
                                                          metrics                            <- ZIO.service[MetricsRegistry]
                                                          runtimeHealth                      <- ZIO.service[RuntimeHealth]
                                                          auditSink                          <- ZIO.service[AuditSink]
                                                          capabilityCheck                    <- ZIO.service[CapabilityCheck]
                                                          rateLimiter                        <- ZIO.service[RateLimiter]
                                                          runtime                            <- ZIO.runtime[Any]
                                                          uploadIngestor                      = PdfUploadSupport.ingestor(blobStore)
                                                          interceptors                        = verifierOpt.toList.flatMap { verifier =>
                                                                                                  List(
                                                                                                    new AuthInterceptor(verifier, auditSink, runtime),
                                                                                                    new CapabilityInterceptor(capabilityCheck, runtime, Some(auditSink)),
                                                                                                    new RateLimitInterceptor(rateLimiter, runtime),
                                                                                                  )
                                                                                                }
                                                          grpc                               <- GravitonGrpcServer.scoped(
                                                                                                  blobStore,
                                                                                                  uploadIngestor,
                                                                                                  GrpcServerConfig(cfg.grpcPort),
                                                                                                  interceptors,
                                                                                                )
                                                          boundPort                          <- grpc.port
                                                          _                                  <- ZIO.logInfo(s"gRPC API listening on :$boundPort")
                                                          policy                              = Option.when(sec.enabled)(
                                                                                                  HttpSecurityPolicy.make(sec, capabilityCheck, rateLimiter, auditSink)
                                                                                                )
                                                          localizedUpload                     = shardcakeNode.map(_.locality)
                                                          api                                 = HttpApi(
                                                                                                  blobStore = blobStore,
                                                                                                  metrics = Some(MetricsHttpApi(metrics, policy)),
                                                                                                  security = policy,
                                                                                                  localizedUpload = localizedUpload,
                                                                                                )
                                                          publicRoutes: Routes[Any, Nothing]  =
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
                                                                runtimeHealth.refresh
                                                                  .map {
                                                                    case snapshot if snapshot.ready =>
                                                                      Response.json(
                                                                        Json
                                                                          .Obj(
                                                                            "status"  -> Json.Str("ready"),
                                                                            "version" -> Json.Str(_root_.graviton.server.BuildInfo.version),
                                                                          )
                                                                          .toJson
                                                                      )
                                                                    case _                          =>
                                                                      Response
                                                                        .json(Json.Obj("status" -> Json.Str("not_ready")).toJson)
                                                                        .copy(status = Status.ServiceUnavailable)
                                                                  }
                                                              },
                                                            )

                                                          statsRoutes: Routes[Any, Nothing]   =
                                                            Routes(
                                                              Method.GET / "api" / "stats" -> Handler.fromFunctionZIO[Request] { request =>
                                                                val resource = ResourceRef(ResourceKind.Observability, None)
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
                                                                          response
                                                                            .flatMap(result => active.recordOutcome("observability.stats.read", resource, result).as(result)),
                                                                      )
                                                              }
                                                            )

                                                          appRoutes: Routes[Any, Nothing]     =
                                                            verifierOpt match
                                                              case Some(verifier) =>
                                                                val decorateFailure =
                                                                  (request: Request, response: Response) => policy.fold(response)(_.addCorsHeaders(request, response))
                                                                (api.routes ++ statsRoutes) @@ AuthMiddleware.required(verifier, auditSink, decorateFailure)
                                                              case None           =>
                                                                api.routes ++ statsRoutes

                                                          devRoutes: Routes[Any, Nothing]     =
                                                            sec.devSharedSecret match
                                                              case Some(secret) if sec.enabled =>
                                                                DevAuthRoutes.routes(secret, sec.oidcIssuer, sec.oidcAudience)
                                                              case _                           =>
                                                                Routes.empty

                                                          consoleRoutes: Routes[Any, Nothing] =
                                                            if console.enabled then
                                                              ConsoleApi(
                                                                catalog,
                                                                blobStore,
                                                                BlobIngest.make(blobStore, localizedUpload),
                                                                Option.when(shardcakeNode.isDefined)(shardcake.node),
                                                                runtimeHealth,
                                                                _root_.graviton.server.BuildInfo.version,
                                                              ).routes
                                                            else Routes.empty
                                                          preflightRoutes                     = if sec.enabled then api.preflightRoutes else Routes.empty
                                                          nonConsoleRoutes                    = publicRoutes ++ preflightRoutes ++ appRoutes ++ devRoutes
                                                          browserApiRoutes                    =
                                                            if sec.enabled then nonConsoleRoutes else Middleware.cors(nonConsoleRoutes)
                                                          routes                              = consoleRoutes ++ browserApiRoutes
                                                          _                                  <- Server.serve(routes)
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

      chunker = Chunker.fixed(UploadChunkSize.applyUnsafe(cfg.chunkSize))
      _      <- Chunker.locally(chunker) {
                  program.provide(
                    Server.defaultWith { server =>
                      val streaming = server.enableRequestStreaming
                      if console.enabled && !console.allowRemoteBinding then streaming.binding("127.0.0.1", port)
                      else streaming.port(port)
                    },
                    blobLayer(cfg, maintenance, blockPersistence),
                    catalogLayer(cfg),
                    shardcakeNodeLayer(shardcake),
                    ZLayer.succeed(healthConfig),
                    RuntimeHealth.live,
                    auditLayer,
                    capabilityLayer(sec),
                    ZLayer.succeed(sec) >>> RateLimiter.live,
                    ZLayer.succeed(MetricsConfig(5.seconds)),
                    prometheus.publisherLayer,
                    prometheus.prometheusLayer,
                    ZioMetricsRegistry.layer,
                  )
                }
    yield ()

  sealed trait ConfigurationError extends Exception

  object ConfigurationError:
    final case class ShardcakeRequiresSharedStorage(blobBackend: String)
        extends Exception(
          s"Shardcake upload locality requires the shared S3 plus PostgreSQL composition, not '$blobBackend'"
        )
        with ConfigurationError
    case object ConsoleRequiresOpenLocalMode
        extends Exception("GRAVITON_CONSOLE_ENABLED requires GRAVITON_SECURITY_ENABLED=false; do not expose the local console publicly")
        with ConfigurationError

  private[server] def validateShardcakeTopology(
    cfg: GravitonConfig,
    shardcake: ShardcakeUploadConfig,
  ): IO[ConfigurationError, Unit] =
    ZIO
      .fail(ConfigurationError.ShardcakeRequiresSharedStorage(cfg.blobBackend))
      .when(shardcake.enabled && !Set("s3", "minio").contains(cfg.blobBackend.toLowerCase))
      .unit

  private[server] def validateConsoleSecurity(
    security: SecurityConfig,
    console: ConsoleConfig,
  ): IO[ConfigurationError, Unit] =
    ZIO.fail(ConfigurationError.ConsoleRequiresOpenLocalMode).when(console.enabled && security.enabled).unit

  private[server] def shardcakeNodeLayer(
    config: ShardcakeUploadConfig
  ): ZLayer[BlobStore & MetricsRegistry, Throwable, Option[ShardcakeNode]] =
    if config.enabled then
      ((ZLayer.service[BlobStore] ++ ZLayer.service[MetricsRegistry] ++ ZLayer.succeed(config)) >>> ShardcakeNode.live)
        .map(environment => ZEnvironment[Option[ShardcakeNode]](Some(environment.get[ShardcakeNode])))
    else ZLayer.succeed(None)

  private[server] def blobLayer(
    cfg: GravitonConfig,
    maintenance: MaintenanceConfig,
    blockPersistence: BlockPersistenceConfig,
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

    val casLayer =
      (storageLayer ++ ZLayer.service[MetricsRegistry] ++ ZLayer.succeed(blockPersistence)) >>>
        CasBlobStore.coordinatedLayerWithMetricsAndPersistence

    (casLayer ++ ZLayer.service[MetricsRegistry]) >>> MetricsBlobStore.layer

  private[server] def catalogLayer(cfg: GravitonConfig): ZLayer[Any, Throwable, Catalog] =
    cfg.blobBackend.toLowerCase match
      case "s3" | "minio" => PgDataSource.layerFromEnv >>> PgCatalog.layer
      case "fs"           => FsCatalog.layer(Path.of(cfg.fs.root))
      case other          =>
        ZLayer.fail(
          new IllegalArgumentException(
            s"Unsupported GRAVITON_BLOB_BACKEND='$other' (expected 's3', 'minio', or 'fs')"
          )
        )

  /** Compatibility entrypoint for embedded tests and callers using defaults. */
  private[server] def blobLayer(cfg: GravitonConfig): ZLayer[MetricsRegistry, Throwable, BlobStore] =
    blobLayer(cfg, MaintenanceConfig.Default, BlockPersistenceConfig.default)

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
