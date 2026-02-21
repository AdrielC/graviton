package graviton.protocol.http

import graviton.runtime.dashboard.DatalakeDashboardService
import graviton.runtime.model.BlobWritePlan
import graviton.runtime.stores.BlobStore
import graviton.shared.ApiModels.*
import graviton.core.keys.{BinaryKey, KeyBits}
import zio.*
import zio.http.*
import zio.json.EncoderOps
import zio.stream.ZStream

import java.nio.charset.StandardCharsets
import zio.Chunk

final case class HttpApi(
  blobStore: BlobStore,
  dashboard: DatalakeDashboardService,
  metrics: Option[MetricsHttpApi] = None,
) {
  private def badRequest(message: String): Response =
    Response.text(message).copy(status = Status.BadRequest)

  private def blobKeyFromId(id: BlobId): Either[String, BinaryKey.Blob] =
    for
      bits <- KeyBits.fromString(id.value)
      blob <- BinaryKey.blob(bits)
    yield blob

  private val uploadBlobHandler: Handler[Any, Nothing, Request, Response] =
    Handler.fromFunctionZIO[Request] { req =>
      req.body.asStream
        .run(blobStore.put(BlobWritePlan()))
        .map { result =>
          val id = BlobId.applyUnsafe(s"${result.key.bits.algo.primaryName}:${result.key.bits.digest.hex.value}:${result.key.bits.size}")
          Response.json(id.toJson)
        }
        .catchAll(err => ZIO.succeed(Response.text(err.getMessage).copy(status = Status.InternalServerError)))
    }

  private val getBlobHandler: Handler[Any, Nothing, (String, Request), Response] =
    Handler.fromFunctionZIO[(String, Request)] { case (rawId, _) =>
      BlobId.either(rawId) match
        case Left(msg)  => ZIO.succeed(badRequest(s"Invalid blob ID: $msg"))
        case Right(id)  =>
          blobKeyFromId(id) match
            case Left(msg)  => ZIO.succeed(badRequest(msg))
            case Right(key) =>
              ZIO.succeed(
                Response(
                  status = Status.Ok,
                  headers = Headers(Header.ContentType(MediaType.application.`octet-stream`)),
                  body = Body.fromStreamChunked(blobStore.get(key)),
                )
              )
    }

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

  val routes: Routes[Any, Nothing] = Routes(
    Method.GET / "api" / "datalake" / "dashboard"            -> snapshotHandler,
    Method.GET / "api" / "datalake" / "dashboard" / "stream" -> streamHandler,
    Method.POST / "api" / "blobs"                            -> uploadBlobHandler,
    Method.GET / "api" / "blobs" / string("id")              -> getBlobHandler,
  ) ++ metrics.map(_.routes).getOrElse(Routes.empty)

  val app: Handler[Any, Nothing, Request, Response] = routes.toHandler
}
