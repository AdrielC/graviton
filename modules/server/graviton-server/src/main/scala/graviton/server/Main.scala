package graviton.server

import graviton.backend.pg.{PgBlobManifestRepo, PgDataSource}
import graviton.backend.s3.S3BlockStore
import graviton.protocol.http.{AuthMiddleware, DevAuthRoutes, HttpApi, MetricsHttpApi}
import graviton.runtime.config.GravitonConfig
import graviton.runtime.dashboard.DatalakeDashboardService
import graviton.runtime.metrics.{InMemoryMetricsRegistry, MetricsRegistry}
import graviton.runtime.stores.{BlobManifestRepo, BlobStore, BlockStore, CasBlobStore, FsBlobManifestRepo, FsBlockStore}
import graviton.security.*
import graviton.security.jwt.HmacJwtVerifier
import graviton.shared.ApiModels.*
import zio.*
import zio.http.*
import zio.json.EncoderOps

import java.nio.file.Path
import java.util.concurrent.TimeUnit

object Main extends ZIOAppDefault:

  override def run: ZIO[Any, Any, Any] =
    for
      cfg                                          <- ZIO.config(GravitonConfig.config)
      sec                                          <- ZIO.config(SecurityConfig.config)
      _                                            <- validateSecurityOrFail(sec)
      _                                            <- logSecurityPosture(sec)
      port                                          = cfg.httpPort
      started                                      <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _                                            <- ZIO.logInfo(s"Starting Graviton server on :$port")
      verifierOpt                                   = buildVerifier(sec)
      program                                       =
        for
          routes <- ZIO.serviceWithZIO[BlobStore] { blobStore =>
                      ZIO.serviceWithZIO[DatalakeDashboardService] { dashboard =>
                        ZIO.serviceWithZIO[MetricsRegistry] { metrics =>
                          ZIO.serviceWithZIO[AuditSink] { auditSink =>
                            val api = HttpApi(
                              blobStore = blobStore,
                              dashboard = dashboard,
                              metrics = Some(MetricsHttpApi(metrics)),
                            )

                            val publicRoutes: Routes[Any, Nothing] =
                              Routes(
                                Method.GET / "api" / "health" -> Handler.fromZIO {
                                  for
                                    now <- Clock.currentTime(TimeUnit.MILLISECONDS)
                                    up   = (now - started).max(0L)
                                  yield Response.json(HealthResponse(status = "ok", version = "dev", uptime = up).toJson)
                                },
                                Method.GET / "api" / "stats"  -> Handler.fromZIO(
                                  metrics.snapshot.map { snapshot =>
                                    Response.json(RuntimeStats.from(snapshot).toJson)
                                  }
                                ),
                                Method.GET / "api" / "schema" -> Handler.succeed(Response.json(List.empty[ObjectSchema].toJson)),
                              )

                            val appRoutes: Routes[Any, Nothing] =
                              verifierOpt match
                                case Some(verifier) =>
                                  api.routes @@ AuthMiddleware.required(verifier, auditSink)
                                case None           =>
                                  api.routes

                            val devRoutes: Routes[Any, Nothing] =
                              sec.devSharedSecret match
                                case Some(secret) if sec.enabled =>
                                  DevAuthRoutes.routes(secret, sec.oidcIssuer, sec.oidcAudience)
                                case _                           =>
                                  Routes.empty

                            val routes = publicRoutes ++ appRoutes ++ devRoutes

                            ZIO.succeed(routes)
                          }
                        }
                      }
                    }
          _      <- Server.serve(routes)
        yield ()

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
             Server.defaultWithPort(port),
             blobLayer(cfg),
             auditLayer,
             DatalakeDashboardService.live,
             InMemoryMetricsRegistry.layer,
             ZLayer.succeed[Clock](Clock.ClockLive),
           )
    yield ()

  private[server] def blobLayer(cfg: GravitonConfig): ZLayer[MetricsRegistry, Throwable, BlobStore] =
    val storageLayer =
      cfg.blobBackend.toLowerCase match
        case "s3" | "minio" =>
          ZLayer.make[BlockStore & BlobManifestRepo](
            PgDataSource.layerFromEnv,
            PgBlobManifestRepo.layer,
            S3BlockStore.layerFromEnv,
          )
        case "fs"           =>
          val root   = Path.of(cfg.fs.root)
          val prefix = cfg.fs.blockPrefix
          ZLayer.make[BlockStore & BlobManifestRepo](
            ZLayer.succeed[BlockStore](new FsBlockStore(root, prefix)),
            ZLayer.succeed[BlobManifestRepo](new FsBlobManifestRepo(root)),
          )
        case other          =>
          ZLayer.fail(
            new IllegalArgumentException(
              s"Unsupported GRAVITON_BLOB_BACKEND='$other' (expected 's3', 'minio', or 'fs')"
            )
          )

    (storageLayer ++ ZLayer.service[MetricsRegistry]) >>> CasBlobStore.layerWithMetrics

  private def validateSecurityOrFail(sec: SecurityConfig): Task[Unit] =
    ZIO
      .fromEither(sec.validate)
      .mapError(msg => new IllegalStateException(s"invalid GRAVITON_SECURITY_* config: $msg"))
      .unit

  private def logSecurityPosture(sec: SecurityConfig): UIO[Unit] =
    if sec.enabled then
      val mode = sec.devSharedSecret match
        case Some(_) => "HS256 dev shared-secret"
        case None    => "OIDC (RS256); wire JwtVerifier at assembly"
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
   * - Security enabled + no dev secret: [[JwtVerifier.denyAll]] is
   *   installed so every request is rejected; operators MUST bind a real
   *   verifier (e.g. zio-jwt's JwtValidator) at assembly time.
   */
  private def buildVerifier(sec: SecurityConfig): Option[JwtVerifier] =
    if !sec.enabled then None
    else
      sec.devSharedSecret match
        case Some(secret) =>
          Some(HmacJwtVerifier.make(secret, sec.clockSkewSeconds, sec.oidcIssuer, sec.oidcAudience))
        case None         =>
          Some(JwtVerifier.denyAll)
