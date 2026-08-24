package graviton.protocol.http

import graviton.core.bytes.Hasher
import graviton.runtime.model.BlobWritePlan
import graviton.runtime.stores.BlobStore
import graviton.shared.ApiModels.*
import graviton.core.keys.{BinaryKey, KeyBits}
import zio.*
import zio.http.*
import zio.json.EncoderOps
import zio.json.ast.Json

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.{ZoneOffset, ZonedDateTime}
import java.time.format.DateTimeFormatter

final case class HttpApi(
  blobStore: BlobStore,
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
      decoded <- scala.util.Try(URLDecoder.decode(rawId, StandardCharsets.UTF_8)).toEither.left.map(_ => "Invalid blob ID encoding")
      id      <- BlobId.either(decoded)
      bits    <- KeyBits.fromString(id.value)
      blob    <- BinaryKey.blob(bits)
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
        .flatMap { result =>
          blobStore.inspect(result.key).flatMap {
            case Some(description) =>
              val id      = BlobId.applyUnsafe(result.key.bits.render)
              val payload = BlobUploadResult(
                blob = toSummary(description.listing),
                freshBlocks = Count.applyUnsafe(result.stats.freshBlocks.toLong),
                duplicateBlocks = Count.applyUnsafe(result.stats.duplicateBlocks.toLong),
                durationSeconds = result.stats.durationSeconds,
              )
              ZIO.succeed(
                Response(
                  status = Status.Created,
                  headers = Headers(
                    Header.Custom("Content-Type", "application/json; charset=utf-8"),
                    Header.Custom("Location", s"/api/blobs/${id.value}"),
                    Header.Custom("ETag", s"\"${result.key.bits.render}\""),
                  ),
                  body = Body.fromString(payload.toJson),
                )
              )
            case None              =>
              ZIO.fail(new IllegalStateException("Persisted upload is missing its manifest"))
          }
        }
        .catchAll {
          case err: IllegalArgumentException =>
            ZIO.succeed(error(Status.BadRequest, "invalid_blob", Option(err.getMessage).getOrElse("Invalid blob")))
          case _                             =>
            ZIO.succeed(error(Status.InternalServerError, "ingest_failed", "Blob ingest failed"))
        }
    }

  private val listBlobsHandler: Handler[Any, Nothing, Request, Response] =
    Handler.fromZIO {
      blobStore.list
        .map(items => Response.json(BlobListResponse(items.map(toSummary).toList).toJson))
        .catchAll(_ => ZIO.succeed(error(Status.InternalServerError, "inventory_failure", "Blob inventory lookup failed")))
    }

  private val inspectBlobHandler: Handler[Any, Nothing, (String, Request), Response] =
    Handler.fromFunctionZIO[(String, Request)] { case (rawId, _) =>
      blobKeyFromId(rawId) match
        case Left(message) =>
          ZIO.succeed(error(Status.BadRequest, "invalid_blob_id", message))
        case Right(key)    =>
          blobStore
            .inspect(key)
            .map {
              case None              => error(Status.NotFound, "blob_not_found", s"Blob not found: ${key.bits.render}")
              case Some(description) => Response.json(toDetails(description).toJson)
            }
            .catchAll(_ => ZIO.succeed(error(Status.InternalServerError, "storage_failure", "Blob manifest lookup failed")))
    }

  private val verifyBlobHandler: Handler[Any, Nothing, (String, Request), Response] =
    Handler.fromFunctionZIO[(String, Request)] { case (rawId, _) =>
      blobKeyFromId(rawId) match
        case Left(message) =>
          ZIO.succeed(error(Status.BadRequest, "invalid_blob_id", message))
        case Right(key)    =>
          blobStore
            .stat(key)
            .flatMap {
              case None       => ZIO.succeed(error(Status.NotFound, "blob_not_found", s"Blob not found: ${key.bits.render}"))
              case Some(stat) =>
                verify(key).map { verified =>
                  Response.json(
                    BlobVerificationResult(
                      id = BlobId.applyUnsafe(key.bits.render),
                      verified = verified,
                      bytesChecked = SizeBytes.applyUnsafe(stat.size.value),
                    ).toJson
                  )
                }
            }
            .catchAll(_ => ZIO.succeed(error(Status.InternalServerError, "verification_failure", "Blob verification failed")))
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

  val routes: Routes[Any, Nothing] = Routes(
    Method.GET / "api" / "blobs"                             -> listBlobsHandler,
    Method.POST / "api" / "blobs"                            -> uploadBlobHandler,
    Method.GET / "api" / "blobs" / string("id") / "metadata" -> inspectBlobHandler,
    Method.POST / "api" / "blobs" / string("id") / "verify"  -> verifyBlobHandler,
    Method.GET / "api" / "blobs" / string("id")              -> getBlobHandler,
    Method.HEAD / "api" / "blobs" / string("id")             -> headBlobHandler,
    Method.DELETE / "api" / "blobs" / string("id")           -> deleteBlobHandler,
  ) ++ metrics.map(_.routes).getOrElse(Routes.empty)

  val app: Handler[Any, Nothing, Request, Response] = routes.toHandler

  private def toSummary(listing: graviton.runtime.model.BlobListing): BlobSummary =
    BlobSummary(
      id = BlobId.applyUnsafe(listing.key.bits.render),
      size = SizeBytes.applyUnsafe(listing.stat.size.value),
      createdAt = listing.stat.lastModified.toEpochMilli,
      digest = listing.stat.digest.hex.value,
      blockCount = Count.applyUnsafe(listing.blockCount.toLong),
    )

  private def toDetails(description: graviton.runtime.model.BlobDescription): BlobDetails =
    BlobDetails(
      summary = toSummary(description.listing),
      blocks = description.blocks.map { block =>
        BlobBlock(
          index = Count.applyUnsafe(block.index),
          contentId = block.key.bits.render,
          offset = SizeBytes.applyUnsafe(block.offset),
          size = SizeBytes.applyUnsafe(block.size),
        )
      }.toList,
    )

  private def verify(key: BinaryKey.Blob): Task[Boolean] =
    for
      hasher <- ZIO
                  .fromEither(Hasher.hasher(key.bits.algo))
                  .mapError(message => new IllegalStateException(message))
      bytes  <- blobStore
                  .get(key)
                  .mapChunksZIO(chunk => ZIO.attempt(hasher.update(chunk.toArray)).as(chunk))
                  .runCount
      digest <- ZIO.fromEither(hasher.digest).mapError(message => new IllegalArgumentException(message))
    yield digest.hex.value == key.bits.digest.hex.value && bytes == key.bits.size
}
