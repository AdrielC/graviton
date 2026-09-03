package graviton.protocol.http

import graviton.core.bytes.Hasher
import graviton.runtime.metrics.MetricKeys
import graviton.runtime.model.{InventoryCursor, InventoryPageSize}
import graviton.runtime.stores.{BlobMetadataV1, BlobStore, StoreError}
import graviton.runtime.upload.{
  LocalityAwareUpload,
  ResumableUploadPhase,
  ResumableUploadRepository,
  ResumableUploadService,
  TenantId,
  UploadHttpHeaders,
  UploadIntent,
  UploadOffset,
  UploadPartId,
  UploadSessionId,
  UploadSessionKey,
  UploadSource,
  UploadSourceError,
}
import graviton.security.{CallerContext, Capability, ResourceRef, SecurityError}
import graviton.shared.{ApiJson, MediaTypeText}
import graviton.shared.ApiModels.*
import graviton.core.keys.BinaryKey
import graviton.core.types.{BlobOffset, FileSize}
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
  resumableUploads: Option[ResumableUploadService] = None,
) {

  /** Binary-compatible constructor for the public HTTP API shipped in 0.6.1. */
  def this(
    blobStore: BlobStore,
    metrics: Option[MetricsHttpApi],
    security: Option[HttpSecurityPolicy],
    localizedUpload: Option[LocalityAwareUpload],
  ) = this(blobStore, metrics, security, localizedUpload, None)

  /** Binary-compatible copy method for the public HTTP API shipped in 0.6.1. */
  def copy(
    blobStore: BlobStore,
    metrics: Option[MetricsHttpApi],
    security: Option[HttpSecurityPolicy],
    localizedUpload: Option[LocalityAwareUpload],
  ): HttpApi =
    new HttpApi(blobStore, metrics, security, localizedUpload, resumableUploads)

  private final case class UploadOutcome(
    key: BinaryKey.Blob,
    stats: graviton.core.attributes.IngestStats,
  )
  private val blobIngest                              = BlobIngest.make(blobStore, localizedUpload)
  private val defaultUploadMediaType: BlocksMediaType =
    BlocksMediaTypes.application.`octet-stream`
  private val defaultResumableTenant: TenantId        =
    TenantId.applyUnsafe("00000000-0000-4000-8000-000000000000")

  private def error(status: Status, code: String, message: String): Response =
    Response(
      status = status,
      headers = Headers(Header.Custom("Content-Type", "application/json; charset=utf-8")),
      body = Body.fromString(ApiJson.encode(ApiError(code, message))),
    )

  private def blobKeyFromId(rawId: String): Either[String, BinaryKey.Blob] =
    BlobKeyIdParser.parse(rawId)

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

  private def resumableUploadLength(request: Request): Either[IllegalArgumentException, Option[FileSize]] =
    request.headers.get(UploadHttpHeaders.UploadLength) match
      case None      => Right(None)
      case Some(raw) => parsePositiveFileSize(raw, UploadHttpHeaders.UploadLength).map(Some(_))

  private def resumableUploadOffset(request: Request): Either[IllegalArgumentException, UploadOffset] =
    request.headers
      .get(UploadHttpHeaders.UploadOffset)
      .toRight(new IllegalArgumentException(s"Missing ${UploadHttpHeaders.UploadOffset} header"))
      .flatMap { raw =>
        if raw.isEmpty || !raw.forall(_.isDigit) then
          Left(new IllegalArgumentException(s"Invalid ${UploadHttpHeaders.UploadOffset}: expected ASCII decimal digits"))
        else
          raw.toLongOption
            .toRight(new IllegalArgumentException(s"Invalid ${UploadHttpHeaders.UploadOffset}: value exceeds a signed 64-bit integer"))
            .flatMap(value => UploadOffset.either(value).left.map(message => new IllegalArgumentException(message)))
      }

  private def resumablePartId(request: Request): Either[IllegalArgumentException, UploadPartId] =
    request.headers
      .get(UploadHttpHeaders.UploadPartId)
      .toRight(new IllegalArgumentException(s"Missing ${UploadHttpHeaders.UploadPartId} header"))
      .flatMap(value => UploadPartId.either(value).left.map(message => new IllegalArgumentException(message)))

  private def parsePositiveFileSize(raw: String, header: String): Either[IllegalArgumentException, FileSize] =
    if raw.isEmpty || !raw.forall(_.isDigit) then Left(new IllegalArgumentException(s"Invalid $header: expected ASCII decimal digits"))
    else
      raw.toLongOption
        .toRight(new IllegalArgumentException(s"Invalid $header: value exceeds a signed 64-bit integer"))
        .flatMap(value => FileSize.either(value).left.map(message => new IllegalArgumentException(s"Invalid $header: $message")))

  private def requestTenant(request: Request): IO[Response, TenantId] =
    CallerContext.current.flatMap {
      case Some(caller) =>
        ZIO
          .fromEither(TenantId.fromUuid(caller.orgId))
          .mapError(_ => error(Status.Unauthorized, "invalid_identity", "Authenticated organization is not a canonical UUID"))
          .flatMap { tenant =>
            request.headers.get(UploadHttpHeaders.TenantId) match
              case None      => ZIO.succeed(tenant)
              case Some(raw) =>
                ZIO
                  .fromEither(TenantId.either(raw))
                  .mapError(message => error(Status.BadRequest, "invalid_tenant", message))
                  .filterOrFail(_ == tenant)(
                    error(Status.Forbidden, "tenant_mismatch", "Upload tenant must match the authenticated organization")
                  )
          }
      case None         =>
        if security.nonEmpty then ZIO.fail(error(Status.Unauthorized, "unauthenticated", "Authentication required"))
        else
          request.headers.get(UploadHttpHeaders.TenantId) match
            case None      => ZIO.succeed(defaultResumableTenant)
            case Some(raw) =>
              ZIO
                .fromEither(TenantId.either(raw))
                .mapError(message => error(Status.BadRequest, "invalid_tenant", message))
    }

  private def requestSessionId(request: Request): IO[Response, UploadSessionId] =
    request.headers.get(UploadHttpHeaders.UploadSession) match
      case Some(raw) =>
        ZIO
          .fromEither(UploadSessionId.either(raw))
          .mapError(message => error(Status.BadRequest, "invalid_upload_id", message))
      case None      => Random.nextUUID.map(value => UploadSessionId.applyUnsafe(value.toString))

  private def resumableKey(rawId: String, request: Request): IO[Response, UploadSessionKey] =
    for
      decoded <- ZIO
                   .attempt(URLDecoder.decode(rawId, StandardCharsets.UTF_8))
                   .mapError(_ => error(Status.BadRequest, "invalid_upload_id", "Invalid upload ID encoding"))
      id      <- ZIO
                   .fromEither(UploadSessionId.either(decoded))
                   .mapError(message => error(Status.BadRequest, "invalid_upload_id", message))
      tenant  <- requestTenant(request)
    yield UploadSessionKey(tenant, id)

  private def resumableHeaders(session: graviton.runtime.upload.ResumableUploadSession): Headers =
    val base = Headers(
      Header.Custom(UploadHttpHeaders.UploadOffset, session.offset.value.toString),
      Header.Custom(UploadHttpHeaders.UploadExpires, session.expiresAt.toString),
      Header.Custom(UploadHttpHeaders.UploadSession, session.key.uploadSessionId.value),
      Header.Custom("Cache-Control", "no-store"),
    )
    session.intent.expectedSize.fold(base)(size => base ++ Headers(Header.Custom(UploadHttpHeaders.UploadLength, size.value.toString)))

  private def toResumableStatus(
    session: graviton.runtime.upload.ResumableUploadSession
  ): ResumableUploadStatus =
    ResumableUploadStatus(
      UploadId.applyUnsafe(session.key.uploadSessionId.value),
      UploadOffsetBytes.applyUnsafe(session.offset.value),
      session.intent.expectedSize.map(size => SizeBytes.applyUnsafe(size.value)),
      session.expiresAt.toEpochMilli,
      session.phase match
        case ResumableUploadPhase.Open       => UploadState.Open
        case ResumableUploadPhase.Committing => UploadState.Committing
        case ResumableUploadPhase.Committed  => UploadState.Committed
        case ResumableUploadPhase.Cancelled  => UploadState.Cancelled,
      session.committedBlob.map(blob => BlobId.applyUnsafe(blob.bits.render)),
    )

  private def resumableResponse(
    session: graviton.runtime.upload.ResumableUploadSession,
    status: Status = Status.Ok,
    body: Boolean = true,
  ): Response =
    val headers =
      if body then resumableHeaders(session) ++ Headers(Header.Custom("Content-Type", "application/json; charset=utf-8"))
      else resumableHeaders(session)
    Response(
      status = status,
      headers = headers,
      body = if body then Body.fromString(ApiJson.encode(toResumableStatus(session))) else Body.empty,
    )

  private def resumableError(errorValue: ResumableUploadService.Error): Response =
    errorValue match
      case ResumableUploadService.Error.NotFound(_)                                                                      =>
        error(Status.NotFound, "upload_not_found", errorValue.getMessage)
      case ResumableUploadService.Error.Repository(value)                                                                =>
        value match
          case _: ResumableUploadRepository.Error.Missing                                                             =>
            error(Status.NotFound, "upload_not_found", value.getMessage)
          case _: ResumableUploadRepository.Error.Expired                                                             =>
            error(Status.Gone, "upload_expired", value.getMessage)
          case mismatch: ResumableUploadRepository.Error.OffsetMismatch                                               =>
            error(Status.Conflict, "upload_offset_mismatch", mismatch.getMessage)
              .addHeader(Header.Custom(UploadHttpHeaders.UploadOffset, mismatch.actual.value.toString))
          case _: ResumableUploadRepository.Error.PartBusy | _: ResumableUploadRepository.Error.CommitBusy            =>
            error(Status.Conflict, "upload_busy", value.getMessage)
          case _: ResumableUploadRepository.Error.AlreadyExists                                                       =>
            error(Status.Conflict, "upload_exists", value.getMessage)
          case _: ResumableUploadRepository.Error.InvalidState                                                        =>
            error(Status.Conflict, "upload_state", value.getMessage)
          case _: ResumableUploadRepository.Error.PartLimitExceeded | _: ResumableUploadRepository.Error.SizeExceeded =>
            error(Status.BadRequest, "invalid_upload", value.getMessage)
          case _                                                                                                      => error(Status.InternalServerError, "upload_repository_failure", "Resumable upload state failed")
      case _: ResumableUploadService.Error.InvalidPart | _: ResumableUploadService.Error.EmptyOrInvalidPart |
          _: ResumableUploadService.Error.Incomplete =>
        error(Status.BadRequest, "invalid_upload", errorValue.getMessage)
      case ResumableUploadService.Error.Finalization(value: BlobIngest.Error.InvalidInput)                               =>
        error(Status.BadRequest, "invalid_blob", value.getMessage)
      case ResumableUploadService.Error.Finalization(_: BlobIngest.Error.Locality)                                       =>
        error(Status.ServiceUnavailable, "locality_failed", "Upload locality could not complete the stream")
      case ResumableUploadService.Error.Staging(_, rejected: HttpSecurityPolicy.BodyRejected)                            =>
        rejected.error match
          case SecurityError.PayloadTooLarge(_) =>
            error(Status.RequestEntityTooLarge, "payload_too_large", "Request payload is too large")
          case SecurityError.RateLimited(_)     =>
            error(Status.TooManyRequests, "rate_limited", "Rate limit exceeded")
          case _                                =>
            error(Status.Forbidden, "forbidden", "Request denied")
      case ResumableUploadService.Error.Source(UploadSourceError.Rejected(_, rejected: HttpSecurityPolicy.BodyRejected)) =>
        rejected.error match
          case SecurityError.PayloadTooLarge(_) =>
            error(Status.RequestEntityTooLarge, "payload_too_large", "Request payload is too large")
          case SecurityError.RateLimited(_)     =>
            error(Status.TooManyRequests, "rate_limited", "Rate limit exceeded")
          case _                                =>
            error(Status.Forbidden, "forbidden", "Request denied")
      case ResumableUploadService.Error.Admission(underlying)                                                            =>
        tenantStorageResponse(underlying).getOrElse(
          error(Status.ServiceUnavailable, "transfer_admission_unavailable", "Transfer admission is temporarily unavailable")
        )
      case _                                                                                                             => error(Status.InternalServerError, "resumable_upload_failure", "Resumable upload failed")

  private val createResumableUploadHandler: Handler[Any, Nothing, Request, Response] =
    Handler.fromFunctionZIO[Request] { request =>
      secured(request, "blob.write", Capability.BlobWrite, ResourceRef.blobCollection) {
        resumableUploads match
          case None          => ZIO.succeed(error(Status.NotImplemented, "resumable_uploads_disabled", "Resumable uploads are not configured"))
          case Some(service) =>
            (for
              tenant    <- requestTenant(request)
              sessionId <- requestSessionId(request)
              mediaType <-
                ZIO.fromEither(uploadMediaType(request)).mapError(value => error(Status.BadRequest, "invalid_upload", value.getMessage))
              length    <- ZIO
                             .fromEither(resumableUploadLength(request))
                             .mapError(value => error(Status.BadRequest, "invalid_upload", value.getMessage))
              created   <- service
                             .create(UploadSessionKey(tenant, sessionId), UploadIntent(mediaType, length))
                             .mapError(resumableError)
            yield resumableResponse(created, Status.Created)
              .addHeader(Header.Custom("Location", s"/api/v1/uploads/${sessionId.value}"))).catchAll(ZIO.succeed(_))
      }
    }

  private val statusResumableUploadHandler: Handler[Any, Nothing, (String, Request), Response] =
    Handler.fromFunctionZIO[(String, Request)] { case (rawId, request) =>
      secured(request, "blob.write", Capability.BlobWrite, ResourceRef.blobCollection) {
        resumableUploads match
          case None          => ZIO.succeed(error(Status.NotImplemented, "resumable_uploads_disabled", "Resumable uploads are not configured"))
          case Some(service) =>
            (for
              key     <- resumableKey(rawId, request)
              current <- service.status(key).mapError(resumableError)
            yield resumableResponse(current, body = request.method != Method.HEAD)).catchAll(ZIO.succeed(_))
      }
    }

  private val appendResumableUploadHandler: Handler[Any, Nothing, (String, Request), Response] =
    Handler.fromFunctionZIO[(String, Request)] { case (rawId, request) =>
      val body = security match
        case None         => ZIO.succeed(UploadSource.fromThrowable(request.body.asStream))
        case Some(policy) => policy.checkedUploadSource(request)

      secured(request, "blob.write", Capability.BlobWrite, ResourceRef.blobCollection) {
        resumableUploads match
          case None          => ZIO.succeed(error(Status.NotImplemented, "resumable_uploads_disabled", "Resumable uploads are not configured"))
          case Some(service) =>
            (for
              key      <- resumableKey(rawId, request)
              offset   <- ZIO
                            .fromEither(resumableUploadOffset(request))
                            .mapError(value => error(Status.BadRequest, "invalid_upload", value.getMessage))
              partId   <-
                ZIO.fromEither(resumablePartId(request)).mapError(value => error(Status.BadRequest, "invalid_upload", value.getMessage))
              partSize <-
                ZIO.fromEither(uploadContentLength(request)).mapError(value => error(Status.BadRequest, "invalid_upload", value.getMessage))
              source   <- body
              appended <- service.appendSource(key, partId, offset, partSize, source).mapError(resumableError)
            yield resumableResponse(appended.session, Status.NoContent, body = false)).catchAll(ZIO.succeed(_))
      }
    }

  private val commitResumableUploadHandler: Handler[Any, Nothing, (String, Request), Response] =
    Handler.fromFunctionZIO[(String, Request)] { case (rawId, request) =>
      secured(request, "blob.write", Capability.BlobWrite, ResourceRef.blobCollection) {
        resumableUploads match
          case None          => ZIO.succeed(error(Status.NotImplemented, "resumable_uploads_disabled", "Resumable uploads are not configured"))
          case Some(service) =>
            (for
              key       <- resumableKey(rawId, request)
              committed <- service
                             .commitSource(key)((intent, source) => blobIngest.uploadResumableSource(key, intent, source).map(_.key))
                             .mapError {
                               case value: ResumableUploadService.Error => resumableError(value)
                               case value: BlobIngest.Error             => resumableError(ResumableUploadService.Error.Finalization(value))
                             }
            yield resumableResponse(committed.session)
              .addHeader(Header.Custom("Location", s"/api/v1/blobs/${committed.blob.bits.render}"))).catchAll(ZIO.succeed(_))
      }
    }

  private val cancelResumableUploadHandler: Handler[Any, Nothing, (String, Request), Response] =
    Handler.fromFunctionZIO[(String, Request)] { case (rawId, request) =>
      secured(request, "blob.write", Capability.BlobWrite, ResourceRef.blobCollection) {
        resumableUploads match
          case None          => ZIO.succeed(error(Status.NotImplemented, "resumable_uploads_disabled", "Resumable uploads are not configured"))
          case Some(service) =>
            (for
              key <- resumableKey(rawId, request)
              _   <- service.cancel(key).mapError(resumableError)
            yield Response.status(Status.NoContent)).catchAll(ZIO.succeed(_))
      }
    }

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
      case None         => effect
      case Some(policy) =>
        policy
          .authorize(request, action, capability, resource)
          .foldZIO(
            response => ZIO.succeed(response),
            _ =>
              effect.flatMap { response =>
                policy.recordOutcome(action, resource, response, bytes) *>
                  ZIO.succeed(policy.addCorsHeaders(request, response))
              },
          )

    for
      started  <- Clock.nanoTime
      response <- guarded
      finished <- Clock.nanoTime
      tags      = Map("action" -> action, "status" -> response.status.code.toString)
      _        <- ZIO.foreachDiscard(metrics)(_.registry.counter(MetricKeys.HttpRequestsTotal, tags))
      _        <- ZIO.foreachDiscard(metrics)(_.registry.histogram(MetricKeys.HttpLatencySeconds, (finished - started).toDouble / 1e9, tags))
      _        <- ZIO.foreachDiscard(metrics)(api =>
                    ZIO.whenDiscard(response.status.code >= 400)(api.registry.counter(MetricKeys.HttpErrorsTotal, tags))
                  )
    yield response

  private def blobHeaders(key: BinaryKey.Blob, stat: graviton.runtime.model.BlobStat, contentType: String): Headers =
    val lastModified = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(stat.lastModified, ZoneOffset.UTC))
    Headers(
      Header.Custom("Content-Type", contentType),
      Header.Custom("Content-Length", stat.size.value.toString),
      Header.Custom("ETag", s"\"${key.bits.render}\""),
      Header.Custom("Last-Modified", lastModified),
      Header.Custom("Cache-Control", "public, max-age=31536000, immutable"),
    )

  private val uploadBlobHandler: Handler[Any, Nothing, Request, Response] =
    Handler.fromFunctionZIO[Request] { req =>
      val contentLength = uploadContentLength(req)
      val body          = security match
        case None         => ZIO.succeed(UploadSource.fromThrowable(req.body.asStream))
        case Some(policy) => policy.checkedUploadSource(req)

      secured(req, "blob.write", Capability.BlobWrite, ResourceRef.blobCollection, contentLength.toOption.flatten.map(_.value)) {
        ZIO
          .fromEither(contentLength)
          .flatMap { expectedSize =>
            ZIO.fromEither(uploadMediaType(req)).flatMap { mediaType =>
              ZIO.fromEither(uploadSession(req)).flatMap { session =>
                authorizeUploadSessionTenant(session) *> body.flatMap { source =>
                  blobIngest
                    .uploadSource(session, UploadIntent(mediaType, expectedSize), source)
                    .map(result => UploadOutcome(result.key, result.stats))
                }
              }
            }
          }
          .flatMap { result =>
            blobStore.stat(result.key).flatMap {
              case Some(stat) =>
                val id       = BlobId.applyUnsafe(result.key.bits.render)
                val basePath = "/api/v1/blobs"
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
            case locality: BlobIngest.Error.Locality       =>
              ZIO.succeed(
                tenantStorageResponse(locality).getOrElse(
                  error(Status.ServiceUnavailable, "locality_failed", "Upload locality could not complete the stream")
                )
              )
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
            case storage: BlobIngest.Error.Storage         =>
              ZIO.succeed(
                tenantStorageResponse(storage.cause).getOrElse(
                  error(Status.InternalServerError, "ingest_failed", "Blob ingest failed")
                )
              )
            case response: Response                        =>
              ZIO.succeed(response)
            case _                                         =>
              ZIO.succeed(error(Status.InternalServerError, "ingest_failed", "Blob ingest failed"))
          }
      }
    }

  private def tenantStorageResponse(cause: Throwable): Option[Response] =
    Iterator
      .iterate(Option(cause))(_.flatMap(value => Option(value.getCause)))
      .takeWhile(_.nonEmpty)
      .flatten
      .collectFirst {
        case _: StoreError.TenantStorageQuotaExceeded                                                  =>
          error(Status.InsufficientStorage, "tenant_storage_quota_exceeded", "Tenant storage quota exceeded")
        case _: StoreError.CapacityExceeded                                                            =>
          error(Status.RequestEntityTooLarge, "tenant_object_limit_exceeded", "Tenant object size limit exceeded")
        case _: StoreError.TenantConcurrencyExceeded                                                   =>
          error(Status.TooManyRequests, "tenant_concurrency_exceeded", "Tenant concurrent operation limit exceeded")
        case _: StoreError.TenantAdmissionUnavailable                                                  =>
          error(Status.ServiceUnavailable, "tenant_admission_unavailable", "Tenant admission is temporarily unavailable")
        case _: StoreError.TenantTransferCapacityExceeded | _: StoreError.DistributedAdmissionRejected =>
          error(Status.TooManyRequests, "transfer_admission_limited", "Transfer admission limit exceeded")
        case _: StoreError.TransferAdmissionTimedOut | _: StoreError.TransferAdmissionSaturated |
            _: StoreError.DistributedAdmissionUnavailable | _: StoreError.DistributedAdmissionLeaseLost =>
          error(Status.ServiceUnavailable, "transfer_admission_unavailable", "Transfer admission is temporarily unavailable")
      }

  private val listBlobsHandler: Handler[Any, Nothing, Request, Response] =
    Handler.fromFunctionZIO[Request] { req =>
      secured(req, "blob.list", Capability.BlobRead, ResourceRef.blobCollection) {
        val parameters = for
          limit  <- req.url.queryParam("limit") match
                      case None      => Right(InventoryPageSize.Default)
                      case Some(raw) =>
                        raw.toIntOption
                          .toRight("limit must be a decimal integer")
                          .flatMap(InventoryPageSize.either)
          cursor <- req.url.queryParam("cursor") match
                      case None        => Right(None)
                      case Some(value) => InventoryCursor.either(value).map(Some(_))
        yield limit -> cursor

        parameters match
          case Left(message)          => ZIO.succeed(error(Status.BadRequest, "invalid_pagination", message))
          case Right((limit, cursor)) =>
            blobStore
              .inventoryPage(cursor, limit)
              .map(page => Response.json(ApiJson.encode(BlobListResponse(page.items.map(toSummary).toList, page.next.map(_.value)))))
              .catchAll {
                case invalid: StoreError.InvalidInput =>
                  ZIO.succeed(error(Status.BadRequest, "invalid_pagination", invalid.reason))
                case _                                =>
                  ZIO.succeed(error(Status.InternalServerError, "inventory_failure", "Blob inventory lookup failed"))
              }
      }
    }

  private val inspectBlobHandler: Handler[Any, Nothing, (String, Request), Response] =
    Handler.fromFunctionZIO[(String, Request)] { case (rawId, req) =>
      blobKeyFromId(rawId) match
        case Left(message) =>
          ZIO.succeed(error(Status.BadRequest, "invalid_blob_id", message))
        case Right(key)    =>
          secured(req, "blob.metadata.read", Capability.BlobRead, ResourceRef.blob(key.bits.render)) {
            val parameters = for
              limit  <- req.url.queryParam("limit") match
                          case None      => Right(InventoryPageSize.Default)
                          case Some(raw) =>
                            raw.toIntOption
                              .toRight("limit must be a decimal integer")
                              .flatMap(InventoryPageSize.either)
              cursor <- req.url.queryParam("cursor") match
                          case None        => Right(None)
                          case Some(value) => InventoryCursor.either(value).map(Some(_))
            yield limit -> cursor

            parameters match
              case Left(message)          => ZIO.succeed(error(Status.BadRequest, "invalid_pagination", message))
              case Right((limit, cursor)) =>
                blobStore
                  .inspectPage(key, cursor, limit)
                  .zipPar(blobStore.metadata(key))
                  .map {
                    case (None, _)                  => error(Status.NotFound, "blob_not_found", s"Blob not found: ${key.bits.render}")
                    case (Some(page), blobMetadata) => Response.json(ApiJson.encode(toDetails(page, blobMetadata)))
                  }
                  .catchAll {
                    case invalid: StoreError.InvalidInput =>
                      ZIO.succeed(error(Status.BadRequest, "invalid_pagination", invalid.reason))
                    case _                                =>
                      ZIO.succeed(error(Status.InternalServerError, "storage_failure", "Blob manifest lookup failed"))
                  }
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
          (for
            caller       <- CallerContext.current
            stat         <- blobStore.stat(key)
            blobMetadata <- blobStore.metadata(key)
          yield (caller, stat, blobMetadata))
            .map {
              case (_, None, _)                       => error(Status.NotFound, "blob_not_found", s"Blob not found: ${key.bits.render}")
              case (caller, Some(stat), blobMetadata) =>
                conditionalResponse(
                  key,
                  stat,
                  request,
                  includeBody,
                  caller,
                  blobMetadata.fold(BlobMetadataV1.DefaultMediaType)(_.canonicalMediaType),
                )
            }
            .catchAll(_ => ZIO.succeed(error(Status.InternalServerError, "storage_failure", "Blob metadata lookup failed")))
        }

  private def conditionalResponse(
    key: BinaryKey.Blob,
    stat: graviton.runtime.model.BlobStat,
    request: Request,
    includeBody: Boolean,
    caller: Option[CallerContext],
    contentType: String,
  ): Response =
    val etag            = s"\"${key.bits.render}\""
    val lastModified    = stat.lastModified.truncatedTo(ChronoUnit.SECONDS)
    val ifMatch         = request.headers.get("If-Match")
    val ifNoneMatch     = request.headers.get("If-None-Match")
    val modifiedSince   = request.headers.get("If-Modified-Since").flatMap(parseHttpDate)
    val unmodifiedSince = request.headers.get("If-Unmodified-Since").flatMap(parseHttpDate)

    if ifMatch.exists(value => value != "*" && !etagListContains(value, etag, allowWeak = false)) then
      Response(status = Status.PreconditionFailed, headers = blobHeaders(key, stat, contentType))
    else if unmodifiedSince.exists(instant => lastModified.isAfter(instant)) then
      Response(status = Status.PreconditionFailed, headers = blobHeaders(key, stat, contentType))
    else if ifNoneMatch.exists(value => value == "*" || etagListContains(value, etag, allowWeak = true)) then
      Response(status = Status.NotModified, headers = withoutHeader(blobHeaders(key, stat, contentType), "Content-Length"))
    else if ifNoneMatch.isEmpty && modifiedSince.exists(instant => !lastModified.isAfter(instant)) then
      Response(status = Status.NotModified, headers = withoutHeader(blobHeaders(key, stat, contentType), "Content-Length"))
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
          val headers = withoutHeader(blobHeaders(key, stat, contentType), "Content-Length") ++ Headers(
            Header.Custom("Content-Length", length.toString),
            Header.Custom("Content-Range", s"bytes ${range.start}-${range.endInclusive}/${stat.size.value}"),
            Header.Custom("Accept-Ranges", "bytes"),
          )
          // SAFETY: parseRange proves non-negative start, positive length, and
          // containment within the refined persisted blob size.
          val stream  = checkedDownload(
            blobStore.getRange(
              key,
              BlobOffset.unsafe(range.start),
              FileSize.unsafe(length),
            ),
            caller,
          )
          Response(
            status = Status.PartialContent,
            headers = headers,
            body = if includeBody then Body.fromStream(stream, length) else Body.empty,
          )
        case None                =>
          Response(
            status = Status.Ok,
            headers = blobHeaders(key, stat, contentType) ++ Headers(Header.Custom("Accept-Ranges", "bytes")),
            body = if includeBody then Body.fromStream(checkedDownload(blobStore.get(key), caller), stat.size.value) else Body.empty,
          )

  private def checkedDownload(
    stream: zio.stream.ZStream[Any, Throwable, Byte],
    caller: Option[CallerContext],
  ): zio.stream.ZStream[Any, Throwable, Byte] =
    security match
      case None         => stream
      case Some(policy) => caller.fold(policy.checkedDownload(stream))(policy.checkedDownload(_, stream))

  private final case class ByteRange(start: Long, endInclusive: Long)

  private def withoutHeader(headers: Headers, name: String): Headers =
    Headers.fromIterable(headers.filterNot(_.headerName.equalsIgnoreCase(name)))

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
    Method.OPTIONS / "api" / "v1" / "uploads"                           -> preflightHandler(Set("POST")),
    Method.OPTIONS / "api" / "v1" / "uploads" / string("id")            -> preflightBlobHandler(Set("DELETE", "GET", "HEAD", "PATCH")),
    Method.OPTIONS / "api" / "v1" / "uploads" / string("id") / "commit" -> preflightBlobHandler(Set("POST")),
  )

  val preflightApp: Handler[Any, Nothing, Request, Response] = preflightRoutes.toHandler

  val routes: Routes[Any, Nothing] = Routes(
    Method.GET / "api" / "v1" / "blobs"                              -> listBlobsHandler,
    Method.POST / "api" / "v1" / "blobs"                             -> uploadBlobHandler,
    Method.GET / "api" / "v1" / "blobs" / string("id") / "metadata"  -> inspectBlobHandler,
    Method.POST / "api" / "v1" / "blobs" / string("id") / "verify"   -> verifyBlobHandler,
    Method.GET / "api" / "v1" / "blobs" / string("id")               -> getBlobHandler,
    Method.HEAD / "api" / "v1" / "blobs" / string("id")              -> headBlobHandler,
    Method.DELETE / "api" / "v1" / "blobs" / string("id")            -> deleteBlobHandler,
    Method.POST / "api" / "v1" / "uploads"                           -> createResumableUploadHandler,
    Method.GET / "api" / "v1" / "uploads" / string("id")             -> statusResumableUploadHandler,
    Method.HEAD / "api" / "v1" / "uploads" / string("id")            -> statusResumableUploadHandler,
    Method.PATCH / "api" / "v1" / "uploads" / string("id")           -> appendResumableUploadHandler,
    Method.POST / "api" / "v1" / "uploads" / string("id") / "commit" -> commitResumableUploadHandler,
    Method.DELETE / "api" / "v1" / "uploads" / string("id")          -> cancelResumableUploadHandler,
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

  private def toDetails(
    description: graviton.runtime.model.BlobInspectionPage,
    metadata: Option[BlobMetadataV1],
  ): BlobDetails =
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
      metadata = metadata.map(value =>
        BlobMetadata(
          schemaVersion = BlobMetadataV1.SchemaVersion,
          codecVersion = BlobMetadataV1.CodecVersion,
          mediaType = value.canonicalMediaType,
          chunker = value.chunker.value,
        )
      ),
      nextCursor = description.next.map(_.value),
    )

  private def verify(key: BinaryKey.Blob): Task[Boolean] =
    for
      hasher <- ZIO
                  .fromEither(Hasher.hasher(key.bits.algo))
                  .mapError(message => new IllegalStateException(message))
      bytes  <- blobStore
                  .get(key)
                  .mapChunksZIO(chunk => ZIO.attempt(hasher.update(chunk)).as(chunk))
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

  /** Binary-compatible factory retained for the published 0.6.1 API. */
  def apply(
    blobStore: BlobStore,
    metrics: Option[MetricsHttpApi],
    security: Option[HttpSecurityPolicy],
    localizedUpload: Option[LocalityAwareUpload],
  ): HttpApi =
    new HttpApi(blobStore, metrics, security, localizedUpload)
