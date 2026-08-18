package graviton.protocol.grpc

import graviton.security.*
import io.grpc.*
import zio.*

/**
 * Charges one request token on the caller's per-principal bucket
 * before the RPC runs. Aborts with `RESOURCE_EXHAUSTED` on deny.
 *
 * Byte-rate limiting (upload/download) is applied at the service layer
 * where the byte count is known; this interceptor protects the control
 * plane (RPC call rate) only.
 */
final class RateLimitInterceptor(limiter: RateLimiter, runtime: Runtime[Any]) extends ServerInterceptor:

  override def interceptCall[ReqT, RespT](
    call: ServerCall[ReqT, RespT],
    headers: Metadata,
    next: ServerCallHandler[ReqT, RespT],
  ): ServerCall.Listener[ReqT] =
    AuthInterceptor.currentCallContext match
      case None      =>
        call.close(Status.UNAUTHENTICATED.withDescription("missing caller context"), new Metadata())
        new ServerCall.Listener[ReqT]() {}
      case Some(ctx) =>
        val allowed = Unsafe.unsafe { implicit u =>
          runtime.unsafe
            .run(CallerContext.scopedWith(ctx)(limiter.check(RateLimiter.Kind.Request, 1L)).either)
            .getOrThrowFiberFailure()
        }
        allowed match
          case Left(err) =>
            val status =
              err match
                case SecurityError.RateLimited(msg) => Status.RESOURCE_EXHAUSTED.withDescription(msg)
                case other                          => Status.INTERNAL.withDescription(other.message)
            call.close(status, new Metadata())
            new ServerCall.Listener[ReqT]() {}
          case Right(_)  =>
            next.startCall(call, headers)
