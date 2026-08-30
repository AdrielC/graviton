package graviton.protocol.http

import graviton.security.*
import zio.*
import zio.http.*

import java.net.Inet6Address
import java.util.UUID
import scala.util.Try

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
    trustProxyHeaders: Boolean = false,
  ): Middleware[Any] =
    scoped(verifier, auditSink, decorateFailure, optional = false, trustProxyHeaders = trustProxyHeaders)

  /** Applies only when the header is present — used for partially-public routes. */
  def optional(
    verifier: JwtVerifier,
    auditSink: AuditSink,
    decorateFailure: (Request, Response) => Response = (_, response) => response,
    trustProxyHeaders: Boolean = false,
  ): Middleware[Any] =
    scoped(verifier, auditSink, decorateFailure, optional = true, trustProxyHeaders = trustProxyHeaders)

  /**
   * Wrap the complete handler effect in a FiberRef region. Setting the ref in
   * an incoming interceptor can leak identity when a server fiber is reused.
   */
  private def scoped(
    verifier: JwtVerifier,
    auditSink: AuditSink,
    decorateFailure: (Request, Response) => Response,
    optional: Boolean,
    trustProxyHeaders: Boolean,
  ): Middleware[Any] =
    new Middleware[Any]:
      override def apply[Env1, Err](routes: Routes[Env1, Err]): Routes[Env1, Err] =
        routes.transform[Env1] { downstream =>
          Handler.scoped[Env1] {
            Handler.fromFunctionZIO[Request] { request =>
              if optional && request.headers.get("Authorization").isEmpty then downstream(request)
              else
                authenticate(verifier, auditSink, request, trustProxyHeaders)
                  .mapError(response => decorateFailure(request, response))
                  .flatMap(context => CallerContext.scopedWith(context)(downstream(request)))
            }
          }
        }

  private def authenticate(
    verifier: JwtVerifier,
    auditSink: AuditSink,
    req: Request,
    trustProxyHeaders: Boolean,
  ): ZIO[Any, Response, CallerContext] =
    val requestId = UUID.randomUUID()
    extractBearer(req) match
      case None        =>
        auditSink
          .authFail("http.auth", requestId, "missing bearer token", sourceIp(req, trustProxyHeaders))
          .ignore *> ZIO.fail(unauthorized("missing bearer token"))
      case Some(token) =>
        verifier
          .verify(token, requestId)
          .foldZIO(
            err => authFailResponse(auditSink, requestId, req, err, trustProxyHeaders),
            ctx =>
              val enriched = ctx.copy(sourceIp = sourceIp(req, trustProxyHeaders), userAgent = userAgent(req))
              ZIO.succeed(enriched),
          )

  private def extractBearer(req: Request): Option[String] =
    req.headers
      .get("Authorization")
      .collect { case raw if raw.startsWith(BearerPrefix) => raw.drop(BearerPrefix.length).trim }
      .filter(_.nonEmpty)

  private[http] def sourceIp(req: Request, trustProxyHeaders: Boolean): Option[String] =
    Option
      .when(trustProxyHeaders)(req.headers.get("X-Forwarded-For"))
      .flatten
      .flatMap(_.split(",").headOption)
      .flatMap(canonicalSourceIp)

  private def userAgent(req: Request): Option[String] =
    req.headers.get("User-Agent").map(_.trim.take(MaxUserAgentChars)).filter(_.nonEmpty)

  /**
   * Accept only literal IPv4/IPv6 addresses. In particular, never ask the JVM
   * resolver to interpret an attacker-controlled forwarded host name.
   */
  private[http] def canonicalSourceIp(raw: String): Option[String] =
    val value = raw.trim
    val ipv4  = value.split("\\.", -1)
    if ipv4.length == 4 && ipv4.forall(part => part.nonEmpty && part.length <= 3 && part.forall(ch => ch >= '0' && ch <= '9')) then
      val octets = ipv4.map(_.toInt)
      Option.when(octets.forall(value => value >= 0 && value <= 255))(octets.mkString("."))
    else if value.length <= 45 && value.contains(':') && value.forall(ch => ch.isDigit || "abcdefABCDEF:.".contains(ch)) then
      Try(java.net.InetAddress.getByName(value)).toOption.collect { case address: Inet6Address => address.getHostAddress }
    else None

  private def authFailResponse(
    auditSink: AuditSink,
    requestId: UUID,
    req: Request,
    err: SecurityError,
    trustProxyHeaders: Boolean,
  ): ZIO[Any, Response, Nothing] =
    val (status, message) = err match
      case SecurityError.Unauthenticated(msg, _)     => (Status.Unauthorized, msg)
      case SecurityError.Forbidden(msg, _)           => (Status.Forbidden, msg)
      case SecurityError.RateLimited(msg)            => (Status.TooManyRequests, msg)
      case SecurityError.PayloadTooLarge(msg)        => (Status.RequestEntityTooLarge, msg)
      case SecurityError.AuditFailure(msg, _)        => (Status.InternalServerError, "internal error")
      case SecurityError.MisconfiguredSecurity(_, _) => (Status.InternalServerError, "server misconfigured")

    auditSink.authFail("http.auth", requestId, err.message, sourceIp(req, trustProxyHeaders)).ignore *>
      ZIO.fail(Response.text(message).copy(status = status))

  private def unauthorized(msg: String): Response =
    Response.text(msg).copy(status = Status.Unauthorized)

  private val MaxUserAgentChars = 1024
