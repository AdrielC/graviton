package graviton.protocol.http

import graviton.runtime.dashboard.DatalakeDashboardService
import graviton.runtime.model.BlobWritePlan
import graviton.runtime.stores.BlobStore
import graviton.shared.ApiModels.*
import graviton.core.keys.{BinaryKey, KeyBits}
import zio.*
import zio.http.*
import zio.json.EncoderOps
import zio.json.ast.Json
import zio.stream.ZStream

import java.nio.charset.StandardCharsets
import java.time.{ZoneOffset, ZonedDateTime}
import java.time.format.DateTimeFormatter
import zio.Chunk

final case class HttpApi(
  blobStore: BlobStore,
  dashboard: DatalakeDashboardService,
  metrics: Option[MetricsHttpApi] = None,
) {
  private def error(status: Status, code: String, message: String): Response =
    Response(
      status = status,
      headers = Headers(Header.Custom("Content-Type", "application/json; charset=utf-8")),
      body = Body.fromString(
        Json
          .Obj(
            "error"   -> Json.Str(code),
            "message" -> Json.Str(message),
          )
          .toJson
      ),
    )

  private def blobKeyFromId(rawId: String): Either[String, BinaryKey.Blob] =
    for
      id   <- BlobId.either(rawId)
      bits <- KeyBits.fromString(id.value)
      blob <- BinaryKey.blob(bits)
    yield blob

  private def blobHeaders(key: BinaryKey.Blob, stat: graviton.runtime.model.BlobStat): Headers =
    val lastModified = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(stat.lastModified, ZoneOffset.UTC))
    Headers(
      Header.ContentType(MediaType.application.`octet-stream`),
      Header.Custom("Content-Length", stat.size.value.toString),
      Header.Custom("ETag", s"\"${key.bits.render}\""),
      Header.Custom("Last-Modified", lastModified),
      Header.Custom("Cache-Control", "public, max-age=31536000, immutable"),
    )

  private val uploadBlobHandler: Handler[Any, Nothing, Request, Response] =
    Handler.fromFunctionZIO[Request] { req =>
      req.body.asStream
        .run(blobStore.put(BlobWritePlan()))
        .map { result =>
          val id = BlobId.applyUnsafe(result.key.bits.render)
          Response(
            status = Status.Created,
            headers = Headers(
              Header.Custom("Content-Type", "application/json; charset=utf-8"),
              Header.Custom("Location", s"/api/blobs/${id.value}"),
              Header.Custom("ETag", s"\"${result.key.bits.render}\""),
            ),
            body = Body.fromString(id.toJson),
          )
        }
        .catchAll {
          case err: IllegalArgumentException =>
            ZIO.succeed(error(Status.BadRequest, "invalid_blob", Option(err.getMessage).getOrElse("Invalid blob")))
          case _                             =>
            ZIO.succeed(error(Status.InternalServerError, "ingest_failed", "Blob ingest failed"))
        }
    }

  private def respondWithBlob(rawId: String, includeBody: Boolean): UIO[Response] =
    blobKeyFromId(rawId) match
      case Left(message) =>
        ZIO.succeed(error(Status.BadRequest, "invalid_blob_id", message))
      case Right(key)    =>
        blobStore
          .stat(key)
          .map {
            case None       => error(Status.NotFound, "blob_not_found", s"Blob not found: ${key.bits.render}")
            case Some(stat) =>
              Response(
                status = Status.Ok,
                headers = blobHeaders(key, stat),
                body = if includeBody then Body.fromStreamChunked(blobStore.get(key)) else Body.empty,
              )
          }
          .catchAll(_ => ZIO.succeed(error(Status.InternalServerError, "storage_failure", "Blob metadata lookup failed")))

  private val getBlobHandler: Handler[Any, Nothing, (String, Request), Response] =
    Handler.fromFunctionZIO[(String, Request)] { case (rawId, _) => respondWithBlob(rawId, includeBody = true) }

  private val headBlobHandler: Handler[Any, Nothing, (String, Request), Response] =
    Handler.fromFunctionZIO[(String, Request)] { case (rawId, _) => respondWithBlob(rawId, includeBody = false) }

  private val deleteBlobHandler: Handler[Any, Nothing, (String, Request), Response] =
    Handler.fromFunctionZIO[(String, Request)] { case (rawId, _) =>
      blobKeyFromId(rawId) match
        case Left(message) =>
          ZIO.succeed(error(Status.BadRequest, "invalid_blob_id", message))
        case Right(key)    =>
          blobStore
            .stat(key)
            .flatMap {
              case None    => ZIO.succeed(error(Status.NotFound, "blob_not_found", s"Blob not found: ${key.bits.render}"))
              case Some(_) => blobStore.delete(key).as(Response.status(Status.NoContent))
            }
            .catchAll(_ => ZIO.succeed(error(Status.InternalServerError, "storage_failure", "Blob deletion failed")))
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
    Method.HEAD / "api" / "blobs" / string("id")             -> headBlobHandler,
    Method.DELETE / "api" / "blobs" / string("id")           -> deleteBlobHandler,
  ) ++ metrics.map(_.routes).getOrElse(Routes.empty)

  val app: Handler[Any, Nothing, Request, Response] = routes.toHandler
}
