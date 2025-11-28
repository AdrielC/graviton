package graviton.protocol.http

import graviton.runtime.dashboard.DatalakeDashboardService
import graviton.shared.ApiModels.*
import graviton.runtime.stores.BlobStore
import zio.*
import zio.http.*
import zio.json.EncoderOps
import zio.stream.ZStream

import java.nio.charset.StandardCharsets
import zio.Chunk

final case class HttpApi(
  blobStore: BlobStore,
  dashboard: DatalakeDashboardService,
) {

  private val snapshotHandler: Handler[Any, Nothing, Request, Response] =
    Handler.fromZIO {
      for {
        snap <- dashboard.snapshot
        meta <- dashboard.metaschema
        body  = DatalakeDashboardEnvelope(snap, meta).toJson
      } yield Response.json(body)
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
            HeaderNames.contentType  -> HeaderValues.textEventStream,
            HeaderNames.cacheControl -> "no-cache",
          ),
          body = Body.fromStream(byteStream),
        )
      )
    }

  private val routes = Routes(
    Method.GET / "api" / "datalake" / "dashboard"            -> snapshotHandler,
    Method.GET / "api" / "datalake" / "dashboard" / "stream" -> streamHandler,
  )

  val app: Handler[Any, Nothing, Request, Response] = routes.toHandler
}
