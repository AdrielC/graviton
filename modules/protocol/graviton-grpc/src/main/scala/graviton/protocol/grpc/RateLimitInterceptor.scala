package graviton.protocol.grpc

import graviton.security.*
import io.grpc.*
import io.graviton.blobstore.v1.blob_service.{BlobChunk, PutBlobRequest}
import zio.*

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Charges one request token on the caller's per-principal bucket
 * before the RPC runs, each received PutBlob data frame against the upload
 * budget, and each emitted GetBlob frame against the download budget.
 * Aborts with `RESOURCE_EXHAUSTED` on deny without buffering payloads.
 */
final class RateLimitInterceptor(limiter: RateLimiter, runtime: Runtime[Any]) extends ServerInterceptor:

  override def interceptCall[ReqT, RespT](
    call: ServerCall[ReqT, RespT],
    headers: Metadata,
    next: ServerCallHandler[ReqT, RespT],
  ): ServerCall.Listener[ReqT] =
    if call.getMethodDescriptor.getFullMethodName.endsWith("/Health") then next.startCall(call, headers)
    else
      AuthInterceptor.currentCallContext match
        case None      =>
          call.close(Status.UNAUTHENTICATED.withDescription("missing caller context"), new Metadata())
          new ServerCall.Listener[ReqT]() {}
        case Some(ctx) =>
          chargeSync(ctx, RateLimiter.Kind.Request, 1L) match
            case Left(err) =>
              call.close(toStatus(err), new Metadata())
              new ServerCall.Listener[ReqT]() {}
            case Right(_)  =>
              val method  = call.getMethodDescriptor.getFullMethodName
              val stopped = new AtomicBoolean(false)
              val metered = new ForwardingServerCall.SimpleForwardingServerCall[ReqT, RespT](call):

                override def sendMessage(message: RespT): Unit =
                  val tokens = downloadTokens(method, message)
                  if tokens == 0L then call.sendMessage(message)
                  else
                    chargeSync(ctx, RateLimiter.Kind.DownloadBytes, tokens) match
                      case Right(_)  => call.sendMessage(message)
                      case Left(err) => close(toStatus(err), new Metadata())

                override def close(status: Status, trailers: Metadata): Unit =
                  if stopped.compareAndSet(false, true) then call.close(status, trailers)

              val listener = next.startCall(metered, headers)
              new ForwardingServerCallListener.SimpleForwardingServerCallListener[ReqT](listener):

                override def onMessage(message: ReqT): Unit =
                  if !stopped.get() then
                    val tokens = uploadTokens(method, message)
                    if tokens == 0L then listener.onMessage(message)
                    else
                      chargeSync(ctx, RateLimiter.Kind.UploadBytes, tokens) match
                        case Right(_)  => listener.onMessage(message)
                        case Left(err) => metered.close(toStatus(err), new Metadata())

                override def onHalfClose(): Unit =
                  if !stopped.get() then listener.onHalfClose()

                override def onReady(): Unit =
                  if !stopped.get() then listener.onReady()

  private def chargeSync(
    context: CallerContext,
    kind: RateLimiter.Kind,
    tokens: Long,
  ): Either[SecurityError, Unit] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe
        .run(CallerContext.scopedWith(context)(limiter.check(kind, tokens)).either)
        .getOrThrowFiberFailure()
    }

  private def uploadTokens(method: String, message: Any): Long =
    if !method.endsWith("/PutBlob") then 0L
    else
      message match
        case request: PutBlobRequest =>
          request.kind match
            case PutBlobRequest.Kind.Data(value) => value.size.toLong
            case _                               => 0L
        case _                       => 0L

  private def downloadTokens(method: String, message: Any): Long =
    if !method.endsWith("/GetBlob") then 0L
    else
      message match
        case chunk: BlobChunk => chunk.data.size.toLong
        case _                => 0L

  private def toStatus(error: SecurityError): Status =
    error match
      case SecurityError.RateLimited(message) => Status.RESOURCE_EXHAUSTED.withDescription(message)
      case _                                  => Status.INTERNAL.withDescription("rate-limit check failed")
