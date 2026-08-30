package graviton.protocol.http

import graviton.runtime.upload.UploadHttpHeaders
import graviton.security.*
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.json.EncoderOps
import zio.stream.ZStream

/** Enforces transport, origin, capability, rate, size, and audit policy. */
final class HttpSecurityPolicy(
  config: SecurityConfig,
  capabilities: CapabilityCheck,
  rateLimiter: RateLimiter,
  audit: AuditSink,
):

  private val corsAllowedRequestHeaders = Set(
    "authorization",
    "content-type",
    "if-match",
    "if-modified-since",
    "if-none-match",
    "if-range",
    "if-unmodified-since",
    "range",
    UploadHttpHeaders.TenantId.toLowerCase,
    UploadHttpHeaders.UploadSession.toLowerCase,
    UploadHttpHeaders.UploadLength.toLowerCase,
    UploadHttpHeaders.UploadOffset.toLowerCase,
    UploadHttpHeaders.UploadPartId.toLowerCase,
  )

  def authorize(
    request: Request,
    action: String,
    capability: Capability,
    resource: ResourceRef,
  ): IO[Response, Unit] =
    val check =
      enforceTls(request) *>
        enforceOrigin(request) *>
        rateLimiter.check(RateLimiter.Kind.Request, 1L) *>
        capabilities.require(capability, resource)

    check
      .tapError(error => audit.deny(action, resource, error.message).ignore)
      .mapError(toResponse)

  def recordOutcome(
    action: String,
    resource: ResourceRef,
    response: Response,
    bytes: Option[Long] = None,
  ): UIO[Unit] =
    val status = response.status.code
    val event  =
      if status >= 200 && status < 400 then AuditEvent(action, resource, AuditEvent.Outcome.Allow, bytes = bytes)
      else
        AuditEvent(
          action,
          resource,
          AuditEvent.Outcome.Error,
          reason = Some(s"http_status=$status"),
          bytes = bytes,
        )
    audit.record(event).catchAll(error => ZIO.logError(s"audit write failed: ${error.message}"))

  /**
   * Rebuild the body as a checked stream. This covers chunked requests where
   * Content-Length is absent or dishonest, and charges upload bytes as they
   * are consumed rather than buffering the payload.
   */
  def checkedUpload(request: Request): IO[Response, ZStream[Any, Throwable, Byte]] =
    request.headers.get("Content-Length").flatMap(_.toLongOption) match
      case Some(length) if length > config.maxRequestBytes.value =>
        ZIO.fail(toResponse(SecurityError.PayloadTooLarge(s"request exceeds ${config.maxRequestBytes.value} bytes")))
      case Some(length) if length < 0L                           =>
        ZIO.fail(toResponse(SecurityError.PayloadTooLarge("negative Content-Length")))
      case _                                                     =>
        Ref.make(0L).map { consumed =>
          request.body.asStream.mapChunksZIO { chunk =>
            for
              total <- consumed.updateAndGet(_ + chunk.length.toLong)
              _     <-
                if total <= config.maxRequestBytes.value then ZIO.unit
                else
                  ZIO.fail(
                    HttpSecurityPolicy.BodyRejected(SecurityError.PayloadTooLarge(s"request exceeds ${config.maxRequestBytes.value} bytes"))
                  )
              _     <- rateLimiter
                         .check(RateLimiter.Kind.UploadBytes, chunk.length.toLong)
                         .mapError(HttpSecurityPolicy.BodyRejected.apply)
            yield chunk
          }
        }

  /**
   * Charge response bytes as they leave the server. A client that exhausts
   * its byte budget gets a terminated stream instead of consuming unbounded
   * backend bandwidth after the request itself has been admitted.
   */
  def checkedDownload(stream: ZStream[Any, Throwable, Byte]): ZStream[Any, Throwable, Byte] =
    stream.mapChunksZIO { chunk =>
      rateLimiter
        .check(RateLimiter.Kind.DownloadBytes, chunk.length.toLong)
        .mapError(HttpSecurityPolicy.BodyRejected.apply)
        .as(chunk)
    }

  /** Preserve the authenticated identity for response bodies pulled after the handler returns. */
  def checkedDownload(
    caller: CallerContext,
    stream: ZStream[Any, Throwable, Byte],
  ): ZStream[Any, Throwable, Byte] =
    stream.mapChunksZIO { chunk =>
      CallerContext
        .scopedWith(caller)(rateLimiter.check(RateLimiter.Kind.DownloadBytes, chunk.length.toLong))
        .mapError(HttpSecurityPolicy.BodyRejected.apply)
        .as(chunk)
    }

  def addCorsHeaders(request: Request, response: Response): Response =
    request.headers.get("Origin") match
      case Some(origin) if config.corsAllowedOrigins.contains(origin) =>
        response
          .addHeader(Header.Custom("Access-Control-Allow-Origin", origin))
          .addHeader(
            Header.Custom(
              "Access-Control-Expose-Headers",
              s"Accept-Ranges, Content-Length, Content-Range, ETag, Last-Modified, Location, ${UploadHttpHeaders.UploadLength}, ${UploadHttpHeaders.UploadOffset}, ${UploadHttpHeaders.UploadExpires}, ${UploadHttpHeaders.UploadSession}",
            )
          )
          .addHeader(Header.Custom("Vary", "Origin"))
      case _                                                          => response

  /**
   * Answer an authenticated API's browser preflight without requiring a
   * bearer token. The origin, requested method, and requested headers are
   * still fail-closed against the server configuration and the matched route.
   */
  def preflight(request: Request, allowedMethods: Set[String]): UIO[Response] =
    val requestedMethod  = request.headers.get("Access-Control-Request-Method").map(_.trim.toUpperCase)
    val requestedHeaders = request.headers
      .get("Access-Control-Request-Headers")
      .toList
      .flatMap(_.split(","))
      .map(_.trim.toLowerCase)
      .filter(_.nonEmpty)
      .toSet

    val validated =
      enforceTls(request) *>
        enforceOrigin(request) *>
        ZIO
          .fromOption(request.headers.get("Origin"))
          .orElseFail(SecurityError.Forbidden("CORS preflight requires an Origin header"))
          .unit *>
        ZIO
          .fromOption(requestedMethod)
          .orElseFail(SecurityError.Forbidden("CORS preflight requires a requested method"))
          .filterOrFail(allowedMethods.contains)(SecurityError.Forbidden("requested method is not allowed")) *>
        ZIO
          .fail(SecurityError.Forbidden("requested header is not allowed"))
          .when(requestedHeaders.exists(!corsAllowedRequestHeaders.contains(_)))

    validated.fold(
      toResponse,
      _ =>
        Response
          .status(Status.NoContent)
          .addHeader(Header.Custom("Access-Control-Allow-Origin", request.headers.get("Origin").get))
          .addHeader(Header.Custom("Access-Control-Allow-Methods", allowedMethods.toList.sorted.mkString(", ")))
          .addHeader(Header.Custom("Access-Control-Allow-Headers", corsAllowedRequestHeaders.toList.sorted.mkString(", ")))
          .addHeader(Header.Custom("Access-Control-Max-Age", "600"))
          .addHeader(Header.Custom("Vary", "Origin, Access-Control-Request-Method, Access-Control-Request-Headers")),
    )

  private def enforceTls(request: Request): IO[SecurityError, Unit] =
    val directHttps = request.url.toString.toLowerCase.startsWith("https://")
    val proxyHttps  = config.trustProxyHeaders &&
      request.headers.get("X-Forwarded-Proto").exists(_.split(",").headOption.exists(_.trim.equalsIgnoreCase("https")))
    if !config.requireTls || directHttps || proxyHttps then ZIO.unit
    else ZIO.fail(SecurityError.Forbidden("TLS is required"))

  private def enforceOrigin(request: Request): IO[SecurityError, Unit] =
    request.headers.get("Origin") match
      case None         => ZIO.unit
      case Some(origin) =>
        if config.corsAllowedOrigins.contains(origin) then ZIO.unit
        else ZIO.fail(SecurityError.Forbidden("origin is not allowed"))

  private def toResponse(error: SecurityError): Response =
    val (status, code, message) = error match
      case SecurityError.Unauthenticated(_, _)       => (Status.Unauthorized, "unauthenticated", "Authentication required")
      case SecurityError.Forbidden(msg, _)           => (Status.Forbidden, "forbidden", msg)
      case SecurityError.RateLimited(_)              => (Status.TooManyRequests, "rate_limited", "Rate limit exceeded")
      case SecurityError.PayloadTooLarge(_)          => (Status.RequestEntityTooLarge, "payload_too_large", "Request payload is too large")
      case SecurityError.AuditFailure(_, _)          => (Status.InternalServerError, "audit_failure", "Security audit failed")
      case SecurityError.MisconfiguredSecurity(_, _) => (Status.InternalServerError, "security_misconfigured", "Security is misconfigured")
    Response(
      status = status,
      headers = Headers(Header.Custom("Content-Type", "application/json; charset=utf-8")),
      body = Body.fromString(Json.Obj("error" -> Json.Str(code), "message" -> Json.Str(message)).toJson),
    )

object HttpSecurityPolicy:
  final case class BodyRejected(error: SecurityError) extends RuntimeException(error.message)

  def make(
    config: SecurityConfig,
    capabilities: CapabilityCheck,
    rateLimiter: RateLimiter,
    audit: AuditSink,
  ): HttpSecurityPolicy =
    new HttpSecurityPolicy(config, capabilities, rateLimiter, audit)
