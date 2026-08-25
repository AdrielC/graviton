package graviton.protocol.http

import graviton.security.*
import zio.*
import zio.http.*

import java.util.UUID

/**
 * Bearer-token authentication middleware.
 *
 * Extracts `Authorization: Bearer <jwt>` from the request, delegates to
 * the configured [[JwtVerifier]], and installs the resulting
 * [[CallerContext]] on the fiber for the lifetime of the handler. Every
 * downstream resource check, DB read, and audit event sees the same
 * identity.
 *
 * The middleware is a plain handler combinator — no dependency on
 * `HttpAppMiddleware` — so it can be composed into both the
 * `HttpApi.routes` pipeline and the legacy internal pipeline without
 * bringing in the full middleware stack from zio-http.
 *
 * Routes that should stay open (e.g. `/api/health`) should not be wrapped.
 */
object AuthMiddleware:

  private val BearerPrefix = "Bearer "

  /** Mandatory auth: rejects any request without a valid JWT. */
  def required(
    verifier: JwtVerifier,
    auditSink: AuditSink,
    decorateFailure: (Request, Response) => Response = (_, response) => response,
  ): HandlerAspect[Any, Unit] =
    HandlerAspect.interceptIncomingHandler(
      Handler.fromFunctionZIO[Request] { req =>
        authenticate(verifier, auditSink, req).mapError(response => decorateFailure(req, response)).map((req, _))
      }
    )

  /** Applies only when the header is present — used for partially-public routes. */
  def optional(
    verifier: JwtVerifier,
    auditSink: AuditSink,
    decorateFailure: (Request, Response) => Response = (_, response) => response,
  ): HandlerAspect[Any, Unit] =
    HandlerAspect.interceptIncomingHandler(
      Handler.fromFunctionZIO[Request] { req =>
        req.headers.get("Authorization") match
          case None    => ZIO.succeed((req, ()))
          case Some(_) => authenticate(verifier, auditSink, req).mapError(response => decorateFailure(req, response)).map((req, _))
      }
    )

  private def authenticate(
    verifier: JwtVerifier,
    auditSink: AuditSink,
    req: Request,
  ): ZIO[Any, Response, Unit] =
    val requestId = UUID.randomUUID()
    extractBearer(req) match
      case None        =>
        auditSink
          .authFail("http.auth", requestId, "missing bearer token", sourceIp(req))
          .ignore *> ZIO.fail(unauthorized("missing bearer token"))
      case Some(token) =>
        verifier
          .verify(token, requestId)
          .foldZIO(
            err => authFailResponse(auditSink, requestId, req, err),
            ctx =>
              val enriched = ctx.copy(sourceIp = sourceIp(req), userAgent = userAgent(req))
              CallerContext.currentRef.set(Some(enriched)),
          )

  private def extractBearer(req: Request): Option[String] =
    req.headers
      .get("Authorization")
      .collect { case raw if raw.startsWith(BearerPrefix) => raw.drop(BearerPrefix.length).trim }
      .filter(_.nonEmpty)

  private def sourceIp(req: Request): Option[String] =
    req.headers.get("X-Forwarded-For").map(_.split(",").headOption.getOrElse("").trim).filter(_.nonEmpty)

  private def userAgent(req: Request): Option[String] =
    req.headers.get("User-Agent")

  private def authFailResponse(
    auditSink: AuditSink,
    requestId: UUID,
    req: Request,
    err: SecurityError,
  ): ZIO[Any, Response, Nothing] =
    val (status, message) = err match
      case SecurityError.Unauthenticated(msg, _)     => (Status.Unauthorized, msg)
      case SecurityError.Forbidden(msg, _)           => (Status.Forbidden, msg)
      case SecurityError.RateLimited(msg)            => (Status.TooManyRequests, msg)
      case SecurityError.PayloadTooLarge(msg)        => (Status.RequestEntityTooLarge, msg)
      case SecurityError.AuditFailure(msg, _)        => (Status.InternalServerError, "internal error")
      case SecurityError.MisconfiguredSecurity(_, _) => (Status.InternalServerError, "server misconfigured")

    auditSink.authFail("http.auth", requestId, err.message, sourceIp(req)).ignore *>
      ZIO.fail(Response.text(message).copy(status = status))

  private def unauthorized(msg: String): Response =
    Response.text(msg).copy(status = Status.Unauthorized)
