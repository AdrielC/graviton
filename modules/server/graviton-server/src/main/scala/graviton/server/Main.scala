package graviton.server

import graviton.protocol.http.{HttpApi, MetricsHttpApi}
import graviton.runtime.dashboard.DatalakeDashboardService
import graviton.runtime.metrics.{InMemoryMetricsRegistry, MetricsRegistry}
import graviton.runtime.stores.{BlobStore, InMemoryBlobStore}
import graviton.shared.ApiModels.*
import zio.*
import zio.http.*
import zio.json.EncoderOps

import java.util.concurrent.TimeUnit

object Main extends ZIOAppDefault:

  private def envIntOr(name: String, default: Int): Int =
    sys.env.get(name).flatMap(_.trim.toIntOption).getOrElse(default)

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
                            legacyRepo = None,
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
                                    totalBlobs = 0L,
                                    totalBytes = 0L,
                                    uniqueChunks = 0L,
                                    deduplicationRatio = 1.0,
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

      _ <- program.provide(
             Server.defaultWithPort(port),
             InMemoryBlobStore.layer,
             DatalakeDashboardService.live,
             InMemoryMetricsRegistry.layer,
           )
    yield ()
