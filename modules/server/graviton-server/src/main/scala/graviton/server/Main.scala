package graviton.server

import graviton.backend.pg.{PgBlobManifestRepo, PgDataSource}
import graviton.backend.s3.S3BlockStore
import graviton.protocol.http.{HttpApi, MetricsHttpApi}
import graviton.runtime.dashboard.DatalakeDashboardService
import graviton.runtime.metrics.{InMemoryMetricsRegistry, MetricsRegistry}
import graviton.runtime.stores.{BlobStore, BlockStore, CasBlobStore, FsBlockStore}
import graviton.shared.ApiModels.*
import zio.*
import zio.http.*
import zio.json.EncoderOps

import java.nio.file.Path
import java.util.concurrent.TimeUnit

object Main extends ZIOAppDefault:

  private def envIntOr(name: String, default: Int): Int =
    sys.env.get(name).flatMap(_.trim.toIntOption).getOrElse(default)

  private def envOr(name: String, default: String): String =
    sys.env.get(name).map(_.trim).filter(_.nonEmpty).getOrElse(default)

  override def run: ZIO[Any, Any, Any] =
    for
      port    <- ZIO.succeed(envIntOr("GRAVITON_HTTP_PORT", 8081))
      started <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _       <- ZIO.logInfo(s"Starting Graviton server on :$port")
      program  =
        for
          routes <- ZIO.serviceWithZIO[BlobStore] { blobStore =>
                      ZIO.serviceWithZIO[DatalakeDashboardService] { dashboard =>
                        ZIO.serviceWithZIO[MetricsRegistry] { metrics =>
                          val api = HttpApi(
                            blobStore = blobStore,
                            dashboard = dashboard,
                            metrics = Some(MetricsHttpApi(metrics)),
                          )

                          val routes: Routes[Any, Nothing] =
                            Routes(
                              Method.GET / "api" / "health" -> Handler.fromZIO {
                                for
                                  now <- Clock.currentTime(TimeUnit.MILLISECONDS)
                                  up   = (now - started).max(0L)
                                yield Response.json(HealthResponse(status = "ok", version = "dev", uptime = up).toJson)
                              },
                              Method.GET / "api" / "stats"  -> Handler.succeed(
                                Response.json(
                                  SystemStats(
                                    totalBlobs = Count.applyUnsafe(0L),
                                    totalBytes = SizeBytes.applyUnsafe(0L),
                                    uniqueChunks = Count.applyUnsafe(0L),
                                    deduplicationRatio = Ratio.applyUnsafe(1.0),
                                  ).toJson
                                )
                              ),
                              Method.GET / "api" / "schema" -> Handler.succeed(Response.json(List.empty[ObjectSchema].toJson)),
                            ) ++ api.routes

                          ZIO.succeed(routes)
                        }
                      }
                    }
          _      <- Server.serve(routes)
        yield ()

      blobBackend = envOr("GRAVITON_BLOB_BACKEND", "s3").toLowerCase
      blobLayer   =
        blobBackend match
          case "s3" | "minio" =>
            ZLayer.make[BlobStore](
              PgDataSource.layerFromEnv,
              PgBlobManifestRepo.layer,
              S3BlockStore.layerFromEnv,
              CasBlobStore.layer,
            )
          case "fs"           =>
            val root   = Path.of(envOr("GRAVITON_FS_ROOT", "./.graviton"))
            val prefix = envOr("GRAVITON_FS_BLOCK_PREFIX", "cas/blocks")
            ZLayer.make[BlobStore](
              PgDataSource.layerFromEnv,
              PgBlobManifestRepo.layer,
              ZLayer.succeed[BlockStore](new FsBlockStore(root, prefix)),
              CasBlobStore.layer,
            )
          case other          =>
            ZLayer.fail(
              new IllegalArgumentException(
                s"Unsupported GRAVITON_BLOB_BACKEND='$other' (expected 's3', 'minio', or 'fs')"
              )
            )

      _ <- program.provide(
             Server.defaultWithPort(port),
             blobLayer,
             DatalakeDashboardService.live,
             InMemoryMetricsRegistry.layer,
             ZLayer.succeed[Clock](Clock.ClockLive),
             ZLayer.succeed[Random](Random.RandomLive),
           )
    yield ()
