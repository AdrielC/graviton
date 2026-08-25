package graviton.protocol.grpc

import graviton.security.*
import io.grpc.*
import zio.*

/** Enforces and audits the same coarse blob capabilities as the HTTP transport. */
final class CapabilityInterceptor(
  check: CapabilityCheck,
  runtime: Runtime[Any],
  auditSink: Option[AuditSink] = None,
) extends ServerInterceptor:

  override def interceptCall[ReqT, RespT](
    call: ServerCall[ReqT, RespT],
    headers: Metadata,
    next: ServerCallHandler[ReqT, RespT],
  ): ServerCall.Listener[ReqT] =
    required(call.getMethodDescriptor.getFullMethodName) match
      case None             => next.startCall(call, headers)
      case Some(capability) =>
        AuthInterceptor.currentCallContext match
          case None          => reject(call, Status.UNAUTHENTICATED.withDescription("missing caller context"))
          case Some(context) =>
            val result = Unsafe.unsafe { implicit unsafe =>
              runtime.unsafe
                .run(
                  CallerContext.scopedWith(context)(
                    check.require(capability, ResourceRef(ResourceKind.Blob, None)).either
                  )
                )
                .getOrThrowFiberFailure()
            }
            result match
              case Right(_)    =>
                audit(context, call.getMethodDescriptor.getFullMethodName, AuditEvent.Outcome.Allow, None) match
                  case Right(_) => next.startCall(call, headers)
                  case Left(_)  => reject(call, Status.INTERNAL.withDescription("audit recording failed"))
              case Left(error) =>
                audit(
                  context,
                  call.getMethodDescriptor.getFullMethodName,
                  AuditEvent.Outcome.Deny,
                  Some("required blob capability was not granted"),
                ) match
                  case Right(_) => reject(call, status(error))
                  case Left(_)  => reject(call, Status.INTERNAL.withDescription("audit recording failed"))

  private def required(method: String): Option[Capability] =
    if method.endsWith("/PutBlob") then Some(Capability.BlobWrite)
    else if method.endsWith("/DeleteBlob") then Some(Capability.BlobDelete)
    else if method.endsWith("/GetBlob") || method.endsWith("/StatBlob") || method.endsWith("/ListBlobs") || method.endsWith("/InspectBlob")
    then Some(Capability.BlobRead)
    else None

  private def status(error: SecurityError): Status =
    error match
      case SecurityError.Unauthenticated(message, _) => Status.UNAUTHENTICATED.withDescription(message)
      case SecurityError.Forbidden(message, _)       => Status.PERMISSION_DENIED.withDescription(message)
      case SecurityError.RateLimited(message)        => Status.RESOURCE_EXHAUSTED.withDescription(message)
      case _                                         => Status.INTERNAL.withDescription("authorization check failed")

  private def reject[ReqT, RespT](call: ServerCall[ReqT, RespT], status: Status): ServerCall.Listener[ReqT] =
    call.close(status, new Metadata())
    new ServerCall.Listener[ReqT]() {}

  private def audit(
    context: CallerContext,
    method: String,
    outcome: AuditEvent.Outcome,
    reason: Option[String],
  ): Either[SecurityError, Unit] =
    auditSink match
      case None       => Right(())
      case Some(sink) =>
        Unsafe.unsafe { implicit unsafe =>
          runtime.unsafe
            .run(
              CallerContext.scopedWith(context)(
                sink
                  .record(
                    AuditEvent(
                      action = s"grpc.authorize.$method",
                      resource = ResourceRef.blobCollection,
                      outcome = outcome,
                      reason = reason,
                    )
                  )
                  .either
              )
            )
            .getOrThrowFiberFailure()
        }
