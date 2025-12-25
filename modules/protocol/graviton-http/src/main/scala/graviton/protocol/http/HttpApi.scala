package graviton.protocol.http

import graviton.runtime.dashboard.DatalakeDashboardService
import graviton.runtime.model.BlobWritePlan
import graviton.runtime.stores.BlobStore
import graviton.shared.ApiModels.*
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.attributes.BinaryAttributes
import graviton.core.types.Mime
import zio.*
import zio.http.*
import zio.json.EncoderOps
import zio.json.*
import zio.stream.ZStream

import java.nio.charset.StandardCharsets
import zio.Chunk

final case class HttpApi(
  blobStore: BlobStore,
  dashboard: DatalakeDashboardService,
  metrics: Option[MetricsHttpApi] = None,
) {
  private val AttributesHeader = "X-Attributes"

  private def badRequest(message: String): Response =
    Response.text(message).copy(status = Status.BadRequest)

  private def parseAttributes(req: Request): Either[String, Map[String, String]] =
    req.rawHeader(AttributesHeader) match
      case None        => Right(Map.empty)
      case Some(value) =>
        value.fromJson[Map[String, String]] match
          case Left(err)  => Left(s"Invalid $AttributesHeader header (expected JSON object): $err")
          case Right(map) => Right(map)

  private def toBinaryAttributes(custom: Map[String, String], rawContentType: Option[String]): Either[String, BinaryAttributes] =
    val base = custom.foldLeft(BinaryAttributes.empty) { case (attrs, (k, v)) =>
      attrs.advertiseCustom(k, v)
    }

    val withMime =
      rawContentType match
        case None     => base
        case Some(ct) =>
          Mime.either(ct).fold(_ => base, mime => base.advertiseMime(mime))

    withMime.validate.left.map(_.message)

  private def blobKeyFromId(id: BlobId): Either[String, BinaryKey.Blob] =
    for
      bits <- KeyBits.fromString(id.value)
      blob <- BinaryKey.blob(bits)
    yield blob

  private val uploadBlobHandlerV0: Handler[Any, Nothing, Request, Response] =
    Handler.fromFunctionZIO[Request] { req =>
      req.body.asStream
        .run(blobStore.put(BlobWritePlan()))
        .map { result =>
          val id = BlobId(s"${result.key.bits.algo.primaryName}:${result.key.bits.digest.hex.value}:${result.key.bits.size}")
          Response.json(id.toJson)
        }
        .catchAll(err => ZIO.succeed(Response.text(err.getMessage).copy(status = Status.InternalServerError)))
    }

  private val getBlobHandler: Handler[Any, Nothing, (String, Request), Response] =
    Handler.fromFunctionZIO[(String, Request)] { case (rawId, _) =>
      blobKeyFromId(BlobId(rawId)) match
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

  private val uploadBlobHandlerV1: Handler[Any, Nothing, Request, Response] =
    Handler.fromFunctionZIO[Request] { req =>
      val rawContentType = req.rawHeader("content-type").map(_.trim).filter(_.nonEmpty)

      (for
        attributesMap <- ZIO.fromEither(parseAttributes(req)).mapError(new IllegalArgumentException(_))
        attrs0        <- ZIO.fromEither(toBinaryAttributes(attributesMap, rawContentType)).mapError(new IllegalArgumentException(_))
        result        <- req.body.asStream.run(blobStore.put(BlobWritePlan(attributes = attrs0)))
        bits           = result.key.bits
        key            = s"${bits.algo.primaryName}:${bits.digest.hex.value}:${bits.size}"
        hash           = s"${bits.algo.primaryName}:${bits.digest.hex.value}"
        payload        = UploadNodeHttpClient.CompletedBlob(
                           key = key,
                           size = bits.size,
                           hash = hash,
                           attributes = attributesMap,
                         )
      yield Response.json(payload.toJson)).catchAll {
        case _: IllegalArgumentException =>
          // Validation / decoding problems -> 400.
          ZIO.succeed(badRequest("Invalid upload request"))
        case err                         =>
          ZIO.succeed(Response.text(err.getMessage).copy(status = Status.InternalServerError))
      }
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

  private val v0Routes: Routes[Any, Nothing] =
    Routes(
      Method.GET / "api" / "datalake" / "dashboard"            -> snapshotHandler,
      Method.GET / "api" / "datalake" / "dashboard" / "stream" -> streamHandler,
      Method.POST / "api" / "blobs"                            -> uploadBlobHandlerV0,
      Method.GET / "api" / "blobs" / string("id")              -> getBlobHandler,
    )

  private val v1Routes: Routes[Any, Nothing] =
    Routes(
      Method.GET / "api" / "v1" / "datalake" / "dashboard"            -> snapshotHandler,
      Method.GET / "api" / "v1" / "datalake" / "dashboard" / "stream" -> streamHandler,
      Method.POST / "api" / "v1" / "blobs"                            -> uploadBlobHandlerV1,
      Method.GET / "api" / "v1" / "blobs" / string("id")              -> getBlobHandler,
    )

  val routes: Routes[Any, Nothing] =
    v0Routes ++ v1Routes ++ metrics.map(_.routes).getOrElse(Routes.empty)

  val app: Handler[Any, Nothing, Request, Response] = routes.toHandler
}
