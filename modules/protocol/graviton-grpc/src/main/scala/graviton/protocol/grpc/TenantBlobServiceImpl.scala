package graviton.protocol.grpc

import graviton.runtime.stores.BlobStore
import graviton.runtime.tenant.{TenantContext, TenantRoutingError, TenantStoreProvider}
import graviton.runtime.upload.{TenantId, UploadIngestor}
import graviton.security.CallerContext
import io.grpc.{Status, StatusException}
import io.graviton.blobstore.v1.blob_service.*
import io.graviton.blobstore.v1.blob_service.ZioBlobService.RCBlobService
import scalapb.zio_grpc.RequestContext
import zio.*
import zio.stream.ZStream

/** Captures authenticated organization identity before any gRPC stream escapes its call context. */
final class TenantBlobServiceImpl(
  provider: TenantStoreProvider,
  tenantContext: TenantContext,
  ingestorFor: BlobStore => UploadIngestor,
) extends RCBlobService:

  override def putBlob(
    request: ZStream[Any, StatusException, PutBlobRequest],
    context: RequestContext,
  ): IO[StatusException, PutBlobResponse] =
    resolveService(caller(context)).flatMap(_.putBlob(request))

  override def getBlob(request: GetBlobRequest, context: RequestContext): ZStream[Any, StatusException, BlobChunk] =
    ZStream.unwrapScoped[Any](resolveService(caller(context)).map(_.getBlob(request)))

  override def statBlob(request: BlobKey, context: RequestContext): IO[StatusException, StatBlobResponse] =
    resolveService(caller(context)).flatMap(_.statBlob(request))

  override def listBlobs(request: ListBlobsRequest, context: RequestContext): ZStream[Any, StatusException, BlobSummary] =
    ZStream.unwrapScoped[Any](resolveService(caller(context)).map(_.listBlobs(request)))

  override def inspectBlob(request: InspectBlobRequest, context: RequestContext): ZStream[Any, StatusException, BlobBlock] =
    ZStream.unwrapScoped[Any](resolveService(caller(context)).map(_.inspectBlob(request)))

  override def deleteBlob(request: DeleteBlobRequest, context: RequestContext): IO[StatusException, DeleteBlobResponse] =
    resolveService(caller(context)).flatMap(_.deleteBlob(request))

  private def caller(context: RequestContext): Option[CallerContext] =
    Option(context.attributes.get(AuthInterceptor.CallerAttributeKey))

  private def resolveService(capturedCaller: Option[CallerContext]): IO[StatusException, BlobServiceImpl] =
    capturedCaller match
      case None         => ZIO.fail(Status.UNAUTHENTICATED.withDescription("missing caller context").asException())
      case Some(caller) =>
        val tenant = TenantId.applyUnsafe(caller.orgId.toString)
        CallerContext.scopedWith(caller) {
          tenantContext.locally(tenant) {
            provider
              .resolve(tenant)
              .mapError(toStatus)
              .map(binding => new BlobServiceImpl(binding.store, ingestorFor(binding.store)))
          }
        }

  private def toStatus(error: TenantRoutingError): StatusException = error match
    case _: TenantRoutingError.UnknownTenant | _: TenantRoutingError.SuspendedTenant   =>
      Status.PERMISSION_DENIED.withDescription("tenant storage is unavailable").asException()
    case _: TenantRoutingError.InvalidPolicy | _: TenantRoutingError.PolicyUnavailable =>
      Status.UNAVAILABLE.withDescription("tenant storage policy is unavailable").asException()
    case TenantRoutingError.MissingContext                                             =>
      Status.INTERNAL.withDescription("tenant context is unavailable").asException()
