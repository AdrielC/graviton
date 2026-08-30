package graviton.protocol.grpc

import graviton.security.*
import io.grpc.*
import zio.*

import java.util.UUID

/**
 * gRPC server interceptor that:
 *   1. extracts `authorization: Bearer <jwt>` from the call's metadata,
 *   2. validates it via [[JwtVerifier]],
 *   3. stores the resulting [[CallerContext]] in grpc-java's scoped
 *      [[Context]] so downstream transport interceptors can capture it
 *      synchronously through [[AuthInterceptor.currentCallContext]],
 *   4. records authentication acceptance before dispatch, failing closed if
 *      durable audit rejects the event,
 *   5. aborts invalid calls with `UNAUTHENTICATED` / `PERMISSION_DENIED` and
 *      records an authentication-failure event.
 *
 * The interceptor is ZIO-aware via an unsafe boundary: it runs the
 * verifier effect synchronously using [[Unsafe]] because grpc-java's
 * interceptor API is fully blocking. The underlying [[JwtVerifier]]
 * implementations are non-blocking (JWKS cache + crypto ops), so this
 * does not stall the transport thread in steady state.
 */
final class AuthInterceptor(verifier: JwtVerifier, auditSink: AuditSink, runtime: Runtime[Any]) extends ServerInterceptor:

  import AuthInterceptor.*

  override def interceptCall[ReqT, RespT](
    call: ServerCall[ReqT, RespT],
    headers: Metadata,
    next: ServerCallHandler[ReqT, RespT],
  ): ServerCall.Listener[ReqT] =
    val requestId = UUID.randomUUID()
    val method    = call.getMethodDescriptor.getFullMethodName

    if method.endsWith("/Health") then next.startCall(call, headers)
    else
      Option(headers.get(AuthorizationKey)).flatMap(parseBearer) match
        case None        =>
          auditFailAsync(auditSink, method, requestId, "missing bearer token")
          fail(call, Status.UNAUTHENTICATED, "missing bearer token")
        case Some(token) =>
          verifySync(verifier, token, requestId) match
            case Left(err)  =>
              auditFailAsync(auditSink, method, requestId, err.message)
              fail(call, toGrpcStatus(err), err.message)
            case Right(ctx) =>
              auditAllowSync(auditSink, ctx, method) match
                case Left(_)  =>
                  fail(call, Status.INTERNAL, "audit recording failed")
                case Right(_) =>
                  val callCtx        = Context.current.withValue(CallContextKey, ctx)
                  val attributedCall = new ForwardingServerCall.SimpleForwardingServerCall[ReqT, RespT](call):
                    private val attributes = super.getAttributes.toBuilder.set(CallerAttributeKey, ctx).build()

                    override def getAttributes: Attributes = attributes

                  Contexts.interceptCall(callCtx, attributedCall, headers, next)

  private def fail[ReqT, RespT](
    call: ServerCall[ReqT, RespT],
    status: Status,
    message: String,
  ): ServerCall.Listener[ReqT] =
    call.close(status.withDescription(message), new Metadata())
    new ServerCall.Listener[ReqT]() {}

  private def verifySync(verifier: JwtVerifier, token: String, requestId: UUID): Either[SecurityError, CallerContext] =
    Unsafe.unsafe { implicit u =>
      runtime.unsafe
        .run(verifier.verify(token, requestId).either)
        .getOrThrowFiberFailure()
    }

  private def auditFailAsync(auditSink: AuditSink, method: String, requestId: UUID, reason: String): Unit =
    Unsafe.unsafe { implicit u =>
      val _ = runtime.unsafe.fork(
        auditSink.authFail(s"grpc.$method", requestId, reason, None).ignore
      )
    }

  private def auditAllowSync(auditSink: AuditSink, context: CallerContext, method: String): Either[SecurityError, Unit] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe
        .run(
          CallerContext
            .scopedWith(context)(
              auditSink.allow(s"grpc.authenticate.$method", ResourceRef.blobCollection)
            )
            .either
        )
        .getOrThrowFiberFailure()
    }

object AuthInterceptor:

  val AuthorizationKey: Metadata.Key[String] =
    Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

  /** gRPC context key used to carry the resolved [[CallerContext]] through the interceptor chain. */
  val CallContextKey: Context.Key[CallerContext] =
    Context.key[CallerContext]("graviton-caller-context")

  /** Immutable server-call attribute consumed by ZIO gRPC's RequestContext. */
  val CallerAttributeKey: Attributes.Key[CallerContext] =
    Attributes.Key.create[CallerContext]("graviton-caller-context")

  private val BearerPrefix = "Bearer "

  private def parseBearer(raw: String): Option[String] =
    if raw == null || !raw.startsWith(BearerPrefix) then None
    else
      val stripped = raw.drop(BearerPrefix.length).trim
      if stripped.isEmpty then None else Some(stripped)

  private def toGrpcStatus(err: SecurityError): Status =
    err match
      case SecurityError.Unauthenticated(_, _)       => Status.UNAUTHENTICATED
      case SecurityError.Forbidden(_, _)             => Status.PERMISSION_DENIED
      case SecurityError.RateLimited(_)              => Status.RESOURCE_EXHAUSTED
      case SecurityError.PayloadTooLarge(_)          => Status.OUT_OF_RANGE
      case SecurityError.AuditFailure(_, _)          => Status.INTERNAL
      case SecurityError.MisconfiguredSecurity(_, _) => Status.INTERNAL

  /** Read the CallerContext installed by the interceptor. */
  def currentCallContext: Option[CallerContext] =
    Option(CallContextKey.get())
