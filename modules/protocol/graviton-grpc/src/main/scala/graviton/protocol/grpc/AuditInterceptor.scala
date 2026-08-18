package graviton.protocol.grpc

import graviton.security.*
import io.grpc.*
import zio.*

/**
 * Emits one `audit_log` row per successful RPC call. Failures with an
 * authentication/authorization error are already logged by the
 * [[AuthInterceptor]]; this interceptor handles the allow-side.
 *
 * We can't know the resource id from the call envelope alone, so we emit
 * a coarse-grained event keyed by method name and let the service
 * implementations emit finer-grained events (e.g. per blob id) through
 * the [[AuditSink]] directly.
 */
final class AuditInterceptor(auditSink: AuditSink, runtime: Runtime[Any]) extends ServerInterceptor:

  override def interceptCall[ReqT, RespT](
    call: ServerCall[ReqT, RespT],
    headers: Metadata,
    next: ServerCallHandler[ReqT, RespT],
  ): ServerCall.Listener[ReqT] =
    val method = call.getMethodDescriptor.getFullMethodName

    AuthInterceptor.currentCallContext match
      case None      =>
        next.startCall(call, headers)
      case Some(ctx) =>
        val forwarded = new ForwardingServerCall.SimpleForwardingServerCall[ReqT, RespT](call):
          override def close(status: Status, trailers: Metadata): Unit =
            try
              val outcome =
                if status.isOk then AuditEvent.Outcome.Allow
                else if status.getCode == Status.Code.PERMISSION_DENIED then AuditEvent.Outcome.Deny
                else if status.getCode == Status.Code.UNAUTHENTICATED then AuditEvent.Outcome.AuthFail
                else AuditEvent.Outcome.Error

              val event = AuditEvent(
                action = s"grpc.$method",
                resource = ResourceRef(ResourceKind.Blob, None),
                outcome = outcome,
                reason = Option(status.getDescription).filter(_.nonEmpty),
              )

              Unsafe.unsafe { implicit u =>
                runtime.unsafe
                  .run(CallerContext.scopedWith(ctx)(auditSink.record(event)).ignore)
                  .getOrThrowFiberFailure()
              }
            finally super.close(status, trailers)

        next.startCall(forwarded, headers)
