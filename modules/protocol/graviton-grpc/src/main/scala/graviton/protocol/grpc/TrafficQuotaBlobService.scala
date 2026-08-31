package graviton.protocol.grpc

import graviton.runtime.admission.DistributedTrafficQuota
import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import graviton.runtime.upload.TenantId
import graviton.security.CallerContext
import io.grpc.{Status, StatusException}
import io.graviton.blobstore.v1.blob_service.*
import io.graviton.blobstore.v1.blob_service.ZioBlobService.{BlobService, RCBlobService}
import scalapb.zio_grpc.RequestContext
import zio.*
import zio.stream.ZStream

/** ZIO-native request and delivered-egress quota decorator for gRPC. */
final class TrafficQuotaBlobService(
  delegate: RCBlobService,
  quota: DistributedTrafficQuota,
  metrics: MetricsRegistry,
) extends RCBlobService:

  override def putBlob(
    request: ZStream[Any, StatusException, PutBlobRequest],
    context: RequestContext,
  ): IO[StatusException, PutBlobResponse] =
    chargeRequest(context) *> delegate.putBlob(request, context)

  override def getBlob(
    request: GetBlobRequest,
    context: RequestContext,
  ): ZStream[Any, StatusException, BlobChunk] =
    ZStream
      .unwrap(chargeRequest(context).as(delegate.getBlob(request, context)))
      .mapZIO { frame =>
        val bytes = frame.data.size.toLong
        if bytes == 0L then ZIO.succeed(frame)
        else
          charge(context, DistributedTrafficQuota.Kind.DeliveredEgress, bytes) *>
            metrics.counterBy(MetricKeys.DeliveredEgressBytesTotal, bytes, Map("protocol" -> "grpc")).as(frame)
      }

  override def statBlob(request: BlobKey, context: RequestContext): IO[StatusException, StatBlobResponse] =
    chargeRequest(context) *> delegate.statBlob(request, context)

  override def listBlobs(
    request: ListBlobsRequest,
    context: RequestContext,
  ): ZStream[Any, StatusException, BlobSummary] =
    ZStream.unwrap(chargeRequest(context).as(delegate.listBlobs(request, context)))

  override def inspectBlob(
    request: InspectBlobRequest,
    context: RequestContext,
  ): ZStream[Any, StatusException, BlobBlock] =
    ZStream.unwrap(chargeRequest(context).as(delegate.inspectBlob(request, context)))

  override def deleteBlob(
    request: DeleteBlobRequest,
    context: RequestContext,
  ): IO[StatusException, DeleteBlobResponse] =
    chargeRequest(context) *> delegate.deleteBlob(request, context)

  private def chargeRequest(context: RequestContext): IO[StatusException, Unit] =
    charge(context, DistributedTrafficQuota.Kind.Request, 1L)

  private def charge(
    context: RequestContext,
    kind: DistributedTrafficQuota.Kind,
    amount: Long,
  ): IO[StatusException, Unit] =
    for
      caller <- ZIO
                  .fromOption(Option(context.attributes.get(AuthInterceptor.CallerAttributeKey)))
                  .orElseFail(Status.UNAUTHENTICATED.withDescription("missing caller context").asException())
      tenant <- ZIO
                  .fromEither(TenantId.fromUuid(caller.orgId))
                  .mapError(_ => Status.UNAUTHENTICATED.withDescription("organization claim is not a canonical UUID").asException())
      _      <- CallerContext.scopedWith(caller)(quota.charge(tenant, kind, amount)).mapError(toStatus)
    yield ()

  private def toStatus(error: DistributedTrafficQuota.Error): StatusException =
    error match
      case _: DistributedTrafficQuota.Error.Rejected                                                =>
        Status.RESOURCE_EXHAUSTED.withDescription("distributed traffic quota exceeded").asException()
      case _: DistributedTrafficQuota.Error.Unavailable | _: DistributedTrafficQuota.Error.Protocol =>
        Status.UNAVAILABLE.withDescription("distributed traffic quota is temporarily unavailable").asException()
      case _: DistributedTrafficQuota.Error.InvalidCharge                                           =>
        Status.INTERNAL.withDescription("distributed traffic quota rejected an internal charge").asException()

object TrafficQuotaBlobService:

  /** Explicit optional wiring for the distributed quota decorator. */
  final case class Dependencies(
    quota: DistributedTrafficQuota,
    metrics: MetricsRegistry,
  )

  /** Adapts the context-free service while retaining transport attributes for decorators. */
  def contextual(delegate: BlobService): RCBlobService = new RCBlobService:
    override def putBlob(
      request: ZStream[Any, StatusException, PutBlobRequest],
      context: RequestContext,
    ): IO[StatusException, PutBlobResponse] = delegate.putBlob(request)

    override def getBlob(request: GetBlobRequest, context: RequestContext): ZStream[Any, StatusException, BlobChunk] =
      delegate.getBlob(request)

    override def statBlob(request: BlobKey, context: RequestContext): IO[StatusException, StatBlobResponse] =
      delegate.statBlob(request)

    override def listBlobs(request: ListBlobsRequest, context: RequestContext): ZStream[Any, StatusException, BlobSummary] =
      delegate.listBlobs(request)

    override def inspectBlob(request: InspectBlobRequest, context: RequestContext): ZStream[Any, StatusException, BlobBlock] =
      delegate.inspectBlob(request)

    override def deleteBlob(request: DeleteBlobRequest, context: RequestContext): IO[StatusException, DeleteBlobResponse] =
      delegate.deleteBlob(request)
