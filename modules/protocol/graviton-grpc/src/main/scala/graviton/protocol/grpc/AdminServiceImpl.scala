package graviton.protocol.grpc

import graviton.runtime.stores.BlobStore
import io.grpc.{Status, StatusException}
import io.graviton.blobstore.v1.admin_service.*
import io.graviton.blobstore.v1.admin_service.ZioAdminService.AdminService
import zio.IO

final class AdminServiceImpl(blobStore: BlobStore) extends AdminService:
  override def health(request: HealthRequest): IO[StatusException, HealthResponse] =
    blobStore.healthCheck
      .as(HealthResponse(status = HealthResponse.ServingStatus.SERVING))
      .mapError(error => Status.UNAVAILABLE.withDescription("storage backend is unavailable").withCause(error).asException())
