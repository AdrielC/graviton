package graviton.protocol.http

import graviton.core.bytes.Hasher
import graviton.runtime.metrics.MetricKeys
import graviton.runtime.stores.BlobStore
import graviton.runtime.upload.{LocalityAwareUpload, TenantId, UploadHttpHeaders, UploadIntent, UploadSessionId, UploadSessionKey}
import graviton.security.{CallerContext, Capability, ResourceRef, SecurityError}
import graviton.shared.{ApiJson, MediaTypeText}
import graviton.shared.ApiModels.*
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.types.FileSize
import zio.*
import zio.http.*
import zio.blocks.mediatype.{MediaType as BlocksMediaType, MediaTypes as BlocksMediaTypes}

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.{ZoneOffset, ZonedDateTime}
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

final case class HttpApi(
  blobStore: BlobStore,
  metrics: Option[MetricsHttpApi] = None,
  security: Option[HttpSecurityPolicy] = None,
  localizedUpload: Option[LocalityAwareUpload] = None,
) {

  /** Binary-compatible constructor retained for the published 0.5.0 API. */
  def this(
    blobStore: BlobStore,
    metrics: Option[MetricsHttpApi],
    security: Option[HttpSecurityPolicy],
  ) = this(blobStore, metrics, security, None)

  /** Binary-compatible copy retained for the published 0.5.0 API. */
  def copy(
    blobStore: BlobStore,
    metrics: Option[MetricsHttpApi],
    security: Option[HttpSecurityPolicy],
  ): HttpApi =
    new HttpApi(blobStore, metrics, security, None)

  private final case class UploadOutcome(
    key: BinaryKey.Blob,
    stats: graviton.core.attributes.IngestStats,
  )
  private val blobIngest                              = BlobIngest.make(blobStore, localizedUpload)
  private val defaultUploadMediaType: BlocksMediaType =
    BlocksMediaTypes.application.`octet-stream`

  private def error(status: Status, code: String, message: String): Response =
    Response(
      status = status,
      headers = Headers(Header.Custom("Content-Type", "application/json; charset=utf-8")),
      body = Body.fromString(ApiJson.encode(ApiError(code, message))),
    )

  private def blobKeyFromId(rawId: String): Either[String, BinaryKey.Blob] =
    for
      decoded <- scala.util.Try(URLDecoder.decode(rawId, StandardCharsets.UTF_8)).toEither.left.map(_ => "Invalid blob ID encoding")
      id      <- BlobId.either(decoded)
      bits    <- KeyBits.fromString(id.value)
      blob    <- BinaryKey.blob(bits)
    yield blob

  private def uploadMediaType(request: Request): Either[IllegalArgumentException, BlocksMediaType] =
    request.headers.get("Content-Type") match
      case None      => Right(defaultUploadMediaType)
      case Some(raw) =>
        MediaTypeText
          .parse(raw)
          .left
          .map(message => new IllegalArgumentException(s"Invalid Content-Type: $message"))

  private def uploadContentLength(request: Request): Either[IllegalArgumentException, Option[FileSize]] =
    request.headers.get("Content-Length") match
      case None      => Right(None)
      case Some(raw) =>
        if raw.isEmpty || !raw.forall(character => character >= '0' && character <= '9') then
          Left(new IllegalArgumentException("Invalid Content-Length: expected one or more ASCII decimal digits"))
        else
          for
            value <- raw.toLongOption.toRight(new IllegalArgumentException("Invalid Content-Length: value exceeds a signed 64-bit integer"))
            size  <- FileSize.either(value).left.map(message => new IllegalArgumentException(s"Invalid Content-Length: $message"))
          yield Some(size)

  private def uploadSession(request: Request): Either[IllegalArgumentException, Option[UploadSessionKey]] =
    val tenant  = request.headers.get(UploadHttpHeaders.TenantId)
    val session = request.headers.get(UploadHttpHeaders.UploadSession)
    (tenant, session) match
      case (None, None)                          => Right(None)
      case (Some(tenantText), Some(sessionText)) =>
        for
          tenantId  <- TenantId.either(tenantText).left.map(message => new IllegalArgumentException(s"Invalid tenant ID: $message"))
          sessionId <-
            UploadSessionId.either(sessionText).left.map(message => new IllegalArgumentException(s"Invalid upload session ID: $message"))
        yield Some(UploadSessionKey(tenantId, sessionId))
      case _                                     =>
        Left(
          new IllegalArgumentException(
            s"Both ${UploadHttpHeaders.TenantId} and ${UploadHttpHeaders.UploadSession} are required"
          )
        )

  private def authorizeUploadSessionTenant(session: Option[UploadSessionKey]): IO[Response, Unit] =
    (security, session) match
      case (Some(_), Some(key)) =>
        CallerContext.required
          .mapError(_ => error(Status.Unauthorized, "unauthenticated", "Authentication required"))
          .flatMap { caller =>
            ZIO
              .fail(error(Status.Forbidden, "tenant_mismatch", "Upload tenant must match the authenticated organization"))
              .unless(key.tenantId.value == caller.orgId.toString)
              .unit
          }
      case _                    => ZIO.unit

  private def secured(
    request: Request,
    action: String,
    capability: Capability,
    resource: ResourceRef,
    bytes: Option[Long] = None,
  )(effect: UIO[Response]): UIO[Response] =
    val guarded = security match
      case None         => effect.map(versionHeaders(request, _))
      case Some(policy) =>
        policy
          .authorize(request, action, capability, resource)
          .foldZIO(
            response => ZIO.succeed(response),
            _ =>
              effect.flatMap { response =>
                policy.recordOutcome(action, resource, response, bytes) *>
                  ZIO.succeed(policy.addCorsHeaders(request, versionHeaders(request, response)))
              },
          )

    for
      started  <- Clock.nanoTime
      response <- guarded
      finished <- Clock.nanoTime
      tags      = Map("action" -> action, "status" -> response.status.code.toString)
      _        <- ZIO.foreachDiscard(metrics)(_.registry.counter(MetricKeys.HttpRequestsTotal, tags))
      _        <- ZIO.foreachDiscard(metrics)(_.registry.gauge(MetricKeys.HttpLatencySeconds, (finished - started).toDouble / 1e9, tags))
      _        <- ZIO.foreachDiscard(metrics)(api =>
                    ZIO.whenDiscard(response.status.code >= 400)(api.registry.counter(MetricKeys.HttpErrorsTotal, tags))
                  )
    yield response

  private def versionHeaders(request: Request, response: Response): Response =
    if request.url.path.toString.startsWith("/api/blobs") then
      response
        .addHeader(Header.Custom("Deprecation", "true"))
        .addHeader(Header.Custom("Link", "</api/v1/blobs>; rel=successor-version"))
    else response

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
      val contentLength = uploadContentLength(req)
      val body          = security match
        case None         => ZIO.succeed(req.body.asStream)
        case Some(policy) => policy.checkedUpload(req)

      secured(req, "blob.write", Capability.BlobWrite, ResourceRef.blobCollection, contentLength.toOption.flatten.map(_.value)) {
        ZIO
          .fromEither(contentLength)
          .flatMap { expectedSize =>
            ZIO.fromEither(uploadMediaType(req)).flatMap { mediaType =>
              ZIO.fromEither(uploadSession(req)).flatMap { session =>
                authorizeUploadSessionTenant(session) *> body.flatMap { bytes =>
                  blobIngest
                    .upload(session, UploadIntent(mediaType, expectedSize), bytes)
                    .map(result => UploadOutcome(result.key, result.stats))
                }
              }
            }
          }
          .flatMap { result =>
            blobStore.stat(result.key).flatMap {
              case Some(stat) =>
                val id       = BlobId.applyUnsafe(result.key.bits.render)
                val basePath = if req.url.path.toString.startsWith("/api/v1/") then "/api/v1/blobs" else "/api/blobs"
                val listing  = graviton.runtime.model.BlobListing(result.key, stat, result.stats.blockCount)
                val payload  = BlobUploadResult(
                  blob = toSummary(listing),
                  freshBlocks = Count.applyUnsafe(result.stats.freshBlocks.toLong),
                  duplicateBlocks = Count.applyUnsafe(result.stats.duplicateBlocks.toLong),
                  durationSeconds = result.stats.durationSeconds,
                )
                ZIO.succeed(
                  Response(
                    status = Status.Created,
                    headers = Headers(
                      Header.Custom("Content-Type", "application/json; charset=utf-8"),
                      Header.Custom("Location", s"$basePath/${id.value}"),
                      Header.Custom("ETag", s"\"${result.key.bits.render}\""),
                    ),
                    body = Body.fromString(ApiJson.encode(payload)),
                  )
                )
              case None       =>
                ZIO.fail(new IllegalStateException("Persisted upload is missing its manifest summary"))
            }
          }
          .catchAll {
            case rejected: HttpSecurityPolicy.BodyRejected =>
              rejected.error match
                case SecurityError.PayloadTooLarge(_) =>
                  ZIO.succeed(error(Status.RequestEntityTooLarge, "payload_too_large", "Request payload is too large"))
                case SecurityError.RateLimited(_)     =>
                  ZIO.succeed(error(Status.TooManyRequests, "rate_limited", "Rate limit exceeded"))
                case _                                =>
                  ZIO.succeed(error(Status.Forbidden, "forbidden", "Request denied"))
            case err: IllegalArgumentException             =>
              ZIO.succeed(error(Status.BadRequest, "invalid_blob", Option(err.getMessage).getOrElse("Invalid blob")))
            case BlobIngest.Error.LocalityUnavailable      =>
              ZIO.succeed(error(Status.ServiceUnavailable, "locality_unavailable", "Upload locality is not enabled"))
            case _: BlobIngest.Error.Locality              =>
              ZIO.succeed(error(Status.ServiceUnavailable, "locality_failed", "Upload locality could not complete the stream"))
            case invalid: BlobIngest.Error.InvalidInput    =>
              ZIO.succeed(error(Status.BadRequest, "invalid_blob", invalid.message))
            case BlobIngest.Error.Rejected(rejected)       =>
              rejected.error match
                case SecurityError.PayloadTooLarge(_) =>
                  ZIO.succeed(error(Status.RequestEntityTooLarge, "payload_too_large", "Request payload is too large"))
                case SecurityError.RateLimited(_)     =>
                  ZIO.succeed(error(Status.TooManyRequests, "rate_limited", "Rate limit exceeded"))
                case _                                =>
                  ZIO.succeed(error(Status.Forbidden, "forbidden", "Request denied"))
            case _: BlobIngest.Error.Storage               =>
              ZIO.succeed(error(Status.InternalServerError, "ingest_failed", "Blob ingest failed"))
            case response: Response                        =>
              ZIO.succeed(response)
            case _                                         =>
              ZIO.succeed(error(Status.InternalServerError, "ingest_failed", "Blob ingest failed"))
          }
      }
    }

  private val listBlobsHandler: Handler[Any, Nothing, Request, Response] =
    Handler.fromFunctionZIO[Request] { req =>
      secured(req, "blob.list", Capability.BlobRead, ResourceRef.blobCollection) {
        val limitResult = req.url.queryParam("limit") match
          case None      => Right(100)
          case Some(raw) => raw.toIntOption.filter(value => value >= 1 && value <= 1000).toRight("limit must be between 1 and 1000")

        limitResult match
          case Left(message) => ZIO.succeed(error(Status.BadRequest, "invalid_pagination", message))
          case Right(limit)  =>
            blobStore.list
              .map { items =>
                val summaries = items.map(toSummary)
                req.url.queryParam("cursor") match
                  case Some(value) if !summaries.exists(_.id.value == value) =>
                    error(Status.BadRequest, "invalid_pagination", "cursor does not identify a persisted blob")
                  case cursor                                                =>
                    val remaining = cursor match
                      case None        => summaries
                      case Some(value) => summaries.dropWhile(_.id.value != value).drop(1)
                    val page      = remaining.take(limit)
                    val next      = Option.when(remaining.length > limit)(page.last.id.value)
                    Response.json(ApiJson.encode(BlobListResponse(page.toList, next)))
              }
              .catchAll(_ => ZIO.succeed(error(Status.InternalServerError, "inventory_failure", "Blob inventory lookup failed")))
      }
    }

  private val inspectBlobHandler: Handler[Any, Nothing, (String, Request), Response] =
    Handler.fromFunctionZIO[(String, Request)] { case (rawId, req) =>
      blobKeyFromId(rawId) match
        case Left(message) =>
          ZIO.succeed(error(Status.BadRequest, "invalid_blob_id", message))
        case Right(key)    =>
          secured(req, "blob.metadata.read", Capability.BlobRead, ResourceRef.blob(key.bits.render)) {
            blobStore
              .inspect(key)
              .map {
                case None              => error(Status.NotFound, "blob_not_found", s"Blob not found: ${key.bits.render}")
                case Some(description) => Response.json(ApiJson.encode(toDetails(description)))
              }
              .catchAll(_ => ZIO.succeed(error(Status.InternalServerError, "storage_failure", "Blob manifest lookup failed")))
          }
    }

  private val verifyBlobHandler: Handler[Any, Nothing, (String, Request), Response] =
    Handler.fromFunctionZIO[(String, Request)] { case (rawId, req) =>
      blobKeyFromId(rawId) match
        case Left(message) =>
          ZIO.succeed(error(Status.BadRequest, "invalid_blob_id", message))
        case Right(key)    =>
          secured(req, "blob.verify", Capability.BlobRead, ResourceRef.blob(key.bits.render)) {
            blobStore
              .stat(key)
              .flatMap {
                case None       => ZIO.succeed(error(Status.NotFound, "blob_not_found", s"Blob not found: ${key.bits.render}"))
                case Some(stat) =>
                  verify(key).map { verified =>
                    Response.json(
                      ApiJson.encode(
                        BlobVerificationResult(
                          id = BlobId.applyUnsafe(key.bits.render),
                          verified = verified,
                          bytesChecked = SizeBytes.applyUnsafe(stat.size.value),
                        )
                      )
                    )
                  }
              }
              .catchAll(_ => ZIO.succeed(error(Status.InternalServerError, "verification_failure", "Blob verification failed")))
          }
    }

  private def respondWithBlob(rawId: String, request: Request, includeBody: Boolean): UIO[Response] =
    blobKeyFromId(rawId) match
      case Left(message) =>
        ZIO.succeed(error(Status.BadRequest, "invalid_blob_id", message))
      case Right(key)    =>
        secured(request, "blob.read", Capability.BlobRead, ResourceRef.blob(key.bits.render)) {
          blobStore
            .stat(key)
            .map {
              case None       => error(Status.NotFound, "blob_not_found", s"Blob not found: ${key.bits.render}")
              case Some(stat) =>
                conditionalResponse(key, stat, request, includeBody)
            }
            .catchAll(_ => ZIO.succeed(error(Status.InternalServerError, "storage_failure", "Blob metadata lookup failed")))
        }

  private def conditionalResponse(
    key: BinaryKey.Blob,
    stat: graviton.runtime.model.BlobStat,
    request: Request,
    includeBody: Boolean,
  ): Response =
    val etag            = s"\"${key.bits.render}\""
    val lastModified    = stat.lastModified.truncatedTo(ChronoUnit.SECONDS)
    val ifMatch         = request.headers.get("If-Match")
    val ifNoneMatch     = request.headers.get("If-None-Match")
    val modifiedSince   = request.headers.get("If-Modified-Since").flatMap(parseHttpDate)
    val unmodifiedSince = request.headers.get("If-Unmodified-Since").flatMap(parseHttpDate)

    if ifMatch.exists(value => value != "*" && !etagListContains(value, etag, allowWeak = false)) then
      Response(status = Status.PreconditionFailed, headers = blobHeaders(key, stat))
    else if unmodifiedSince.exists(instant => lastModified.isAfter(instant)) then
      Response(status = Status.PreconditionFailed, headers = blobHeaders(key, stat))
    else if ifNoneMatch.exists(value => value == "*" || etagListContains(value, etag, allowWeak = true)) then
      Response(status = Status.NotModified, headers = withoutHeader(blobHeaders(key, stat), "Content-Length"))
    else if ifNoneMatch.isEmpty && modifiedSince.exists(instant => !lastModified.isAfter(instant)) then
      Response(status = Status.NotModified, headers = withoutHeader(blobHeaders(key, stat), "Content-Length"))
    else
      val rangeAllowed =
        request.headers.get("If-Range").forall(value => value == etag || parseHttpDate(value).exists(!lastModified.isAfter(_)))
      val parsedRange  = if rangeAllowed then request.headers.get("Range").map(parseRange(_, stat.size.value)) else None
      parsedRange match
        case Some(Left(message)) =>
          error(Status.RequestedRangeNotSatisfiable, "invalid_range", message)
            .addHeader(Header.Custom("Content-Range", s"bytes */${stat.size.value}"))
        case Some(Right(range))  =>
          val length  = range.endInclusive - range.start + 1L
          val headers = withoutHeader(blobHeaders(key, stat), "Content-Length") ++ Headers(
            Header.Custom("Content-Length", length.toString),
            Header.Custom("Content-Range", s"bytes ${range.start}-${range.endInclusive}/${stat.size.value}"),
            Header.Custom("Accept-Ranges", "bytes"),
          )
          val stream  = checkedDownload(slice(blobStore.get(key), range.start, length))
          Response(
            status = Status.PartialContent,
            headers = headers,
            body = if includeBody then Body.fromStreamChunked(stream) else Body.empty,
          )
        case None                =>
          Response(
            status = Status.Ok,
            headers = blobHeaders(key, stat) ++ Headers(Header.Custom("Accept-Ranges", "bytes")),
            body = if includeBody then Body.fromStreamChunked(checkedDownload(blobStore.get(key))) else Body.empty,
          )

  private def checkedDownload(stream: zio.stream.ZStream[Any, Throwable, Byte]): zio.stream.ZStream[Any, Throwable, Byte] =
    security.fold(stream)(_.checkedDownload(stream))

  private final case class ByteRange(start: Long, endInclusive: Long)

  private def withoutHeader(headers: Headers, name: String): Headers =
    Headers.fromIterable(headers.filterNot(_.headerName.equalsIgnoreCase(name)))

  private def slice(stream: zio.stream.ZStream[Any, Throwable, Byte], start: Long, length: Long): zio.stream.ZStream[Any, Throwable, Byte] =
    val done = new java.util.concurrent.atomic.AtomicBoolean(false)
    stream.chunks
      .takeWhile(_ => !done.get())
      .mapAccum((start, length)) { case ((toDrop, remaining), chunk) =>
        val dropped   = math.min(toDrop, chunk.length.toLong).toInt
        val available = chunk.length - dropped
        val emitted   = math.min(remaining, available.toLong).toInt
        val output    = chunk.drop(dropped).take(emitted)
        val next      = (toDrop - dropped.toLong, remaining - emitted.toLong)
        if next._2 <= 0L then done.set(true)
        (next, output)
      }
      .flatMap(zio.stream.ZStream.fromChunk)

  private def parseRange(raw: String, size: Long): Either[String, ByteRange] =
    if !raw.startsWith("bytes=") || raw.contains(",") then Left("Only one bytes range is supported")
    else
      raw.stripPrefix("bytes=").split("-", -1).toList match
        case startRaw :: endRaw :: Nil if startRaw.nonEmpty =>
          for
            start <- startRaw.toLongOption.toRight("Invalid range start")
            end   <- if endRaw.isEmpty then Right(size - 1L) else endRaw.toLongOption.toRight("Invalid range end")
            _     <- Either.cond(start >= 0L && start < size && end >= start && end < size, (), "Range is not satisfiable")
          yield ByteRange(start, end)
        case "" :: suffixRaw :: Nil                         =>
          for
            suffix <- suffixRaw.toLongOption.toRight("Invalid suffix range")
            _      <- Either.cond(suffix > 0L, (), "Suffix range must be positive")
            length  = suffix.min(size)
          yield ByteRange(size - length, size - 1L)
        case _                                              => Left("Invalid Range header")

  private def etagListContains(raw: String, etag: String, allowWeak: Boolean): Boolean =
    raw.split(",").iterator.map(_.trim).exists { candidate =>
      if allowWeak then candidate.stripPrefix("W/") == etag
      else candidate == etag
    }

  private def parseHttpDate(raw: String): Option[java.time.Instant] =
    scala.util.Try(ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant).toOption

  private val getBlobHandler: Handler[Any, Nothing, (String, Request), Response] =
    Handler.fromFunctionZIO[(String, Request)] { case (rawId, req) => respondWithBlob(rawId, req, includeBody = true) }

  private val headBlobHandler: Handler[Any, Nothing, (String, Request), Response] =
    Handler.fromFunctionZIO[(String, Request)] { case (rawId, req) => respondWithBlob(rawId, req, includeBody = false) }

  private val deleteBlobHandler: Handler[Any, Nothing, (String, Request), Response] =
    Handler.fromFunctionZIO[(String, Request)] { case (rawId, req) =>
      blobKeyFromId(rawId) match
        case Left(message) =>
          ZIO.succeed(error(Status.BadRequest, "invalid_blob_id", message))
        case Right(key)    =>
          secured(req, "blob.delete", Capability.BlobDelete, ResourceRef.blob(key.bits.render)) {
            blobStore
              .stat(key)
              .flatMap {
                case None    => ZIO.succeed(error(Status.NotFound, "blob_not_found", s"Blob not found: ${key.bits.render}"))
                case Some(_) => blobStore.delete(key).as(Response.status(Status.NoContent))
              }
              .catchAll(_ => ZIO.succeed(error(Status.InternalServerError, "storage_failure", "Blob deletion failed")))
          }
    }

  private def preflightHandler(allowedMethods: Set[String]): Handler[Any, Nothing, Request, Response] =
    Handler.fromFunctionZIO[Request](request =>
      security.fold[UIO[Response]](ZIO.succeed(Response.status(Status.NoContent)))(_.preflight(request, allowedMethods))
    )

  private def preflightBlobHandler(allowedMethods: Set[String]): Handler[Any, Nothing, (String, Request), Response] =
    Handler.fromFunctionZIO[(String, Request)] { case (_, request) =>
      security.fold[UIO[Response]](ZIO.succeed(Response.status(Status.NoContent)))(_.preflight(request, allowedMethods))
    }

  /** Routes that browsers must reach before they are allowed to send Authorization. */
  val preflightRoutes: Routes[Any, Nothing] = Routes(
    Method.OPTIONS / "api" / "v1" / "blobs"                             -> preflightHandler(Set("GET", "POST")),
    Method.OPTIONS / "api" / "v1" / "blobs" / string("id")              -> preflightBlobHandler(Set("DELETE", "GET", "HEAD")),
    Method.OPTIONS / "api" / "v1" / "blobs" / string("id") / "metadata" -> preflightBlobHandler(Set("GET")),
    Method.OPTIONS / "api" / "v1" / "blobs" / string("id") / "verify"   -> preflightBlobHandler(Set("POST")),
  )

  val preflightApp: Handler[Any, Nothing, Request, Response] = preflightRoutes.toHandler

  val routes: Routes[Any, Nothing] = Routes(
    Method.GET / "api" / "v1" / "blobs"                             -> listBlobsHandler,
    Method.POST / "api" / "v1" / "blobs"                            -> uploadBlobHandler,
    Method.GET / "api" / "v1" / "blobs" / string("id") / "metadata" -> inspectBlobHandler,
    Method.POST / "api" / "v1" / "blobs" / string("id") / "verify"  -> verifyBlobHandler,
    Method.GET / "api" / "v1" / "blobs" / string("id")              -> getBlobHandler,
    Method.HEAD / "api" / "v1" / "blobs" / string("id")             -> headBlobHandler,
    Method.DELETE / "api" / "v1" / "blobs" / string("id")           -> deleteBlobHandler,
    Method.GET / "api" / "blobs"                                    -> listBlobsHandler,
    Method.POST / "api" / "blobs"                                   -> uploadBlobHandler,
    Method.GET / "api" / "blobs" / string("id") / "metadata"        -> inspectBlobHandler,
    Method.POST / "api" / "blobs" / string("id") / "verify"         -> verifyBlobHandler,
    Method.GET / "api" / "blobs" / string("id")                     -> getBlobHandler,
    Method.HEAD / "api" / "blobs" / string("id")                    -> headBlobHandler,
    Method.DELETE / "api" / "blobs" / string("id")                  -> deleteBlobHandler,
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

object HttpApi:
  /** Binary-compatible factory retained for the published 0.5.0 API. */
  def apply(
    blobStore: BlobStore,
    metrics: Option[MetricsHttpApi],
    security: Option[HttpSecurityPolicy],
  ): HttpApi =
    new HttpApi(blobStore, metrics, security, None)
