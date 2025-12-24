package graviton.protocol.http

import graviton.runtime.dashboard.DatalakeDashboardService
import graviton.runtime.stores.BlobStore
import graviton.shared.ApiModels.*
import zio.*
import zio.http.*
import zio.json.EncoderOps
import zio.stream.ZStream

import java.nio.charset.StandardCharsets
import zio.Chunk

final case class HttpApi(
  blobStore: BlobStore,
  dashboard: DatalakeDashboardService,
  legacyRepo: Option[LegacyRepoHttpApi] = None,
  metrics: Option[MetricsHttpApi] = None,
) {

  private val snapshotHandler: Handler[Any, Nothing, Request, Response] =
    Handler.fromZIO {
      for {
        snap  <- dashboard.snapshot
        meta  <- dashboard.metaschema
        graph <- dashboard.explorer
      } yield Response.json(DatalakeDashboardEnvelope(snap, meta, graph).toJson)
    }

  private val streamHandler: Handler[Any, Nothing, Request, Response] =
    Handler.fromZIO {
      val byteStream: ZStream[Any, Nothing, Byte] =
        dashboard.updates
          .map(_.toJson)
          .map(line => s"data: $line\n\n")
          .map(str => Chunk.fromArray(str.getBytes(StandardCharsets.UTF_8)))
          .flatMap(ZStream.fromChunk)

      ZIO.succeed(
        Response(
          status = Status.Ok,
          headers = Headers(
            Header.Custom("Content-Type", "text/event-stream"),
            Header.Custom("Cache-Control", "no-cache"),
          ),
          body = Body.fromStreamChunked(byteStream),
        )
      )
    }

  private val routes = Routes(
    Method.GET / "api" / "datalake" / "dashboard"            -> snapshotHandler,
    Method.GET / "api" / "datalake" / "dashboard" / "stream" -> streamHandler,
  ) ++ legacyRepo.map(_.routes).getOrElse(Routes.empty) ++ metrics.map(_.routes).getOrElse(Routes.empty)

  val app: Handler[Any, Nothing, Request, Response] = routes.toHandler
}
