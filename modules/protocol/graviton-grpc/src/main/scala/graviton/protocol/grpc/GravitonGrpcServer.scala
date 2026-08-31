package graviton.protocol.grpc

import graviton.runtime.stores.BlobStore
import graviton.runtime.tenant.{TenantContext, TenantStoreProvider}
import graviton.runtime.upload.UploadIngestor
import io.grpc.{ServerCall, ServerCallHandler, ServerInterceptor}
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import scalapb.zio_grpc.{ScopedServer, Server}
import zio.*

final case class GrpcServerConfig(
  port: Int = 9090,
  maxInboundMessageBytes: Int = GrpcProtocol.MaxInboundMessageBytes,
):
  require(port >= 0 && port <= 65535, "gRPC port must be between 0 and 65535")
  require(maxInboundMessageBytes >= GrpcProtocol.MaxChunkBytes, "gRPC message limit must fit one data chunk")

object GravitonGrpcServer:

  def scoped(
    blobStore: BlobStore,
    config: GrpcServerConfig = GrpcServerConfig(),
    interceptors: List[ServerInterceptor] = Nil,
  ): ZIO[Scope, Throwable, Server] =
    scoped(blobStore, UploadIngestor.default(blobStore), config, interceptors, None)

  def scoped(
    blobStore: BlobStore,
    config: GrpcServerConfig,
    interceptors: List[ServerInterceptor],
    trafficQuota: Option[TrafficQuotaBlobService.Dependencies],
  ): ZIO[Scope, Throwable, Server] =
    scoped(blobStore, UploadIngestor.default(blobStore), config, interceptors, trafficQuota)

  def scoped(
    blobStore: BlobStore,
    uploadIngestor: UploadIngestor,
    config: GrpcServerConfig,
    interceptors: List[ServerInterceptor],
  ): ZIO[Scope, Throwable, Server] =
    scoped(blobStore, uploadIngestor, config, interceptors, None)

  def scoped(
    blobStore: BlobStore,
    uploadIngestor: UploadIngestor,
    config: GrpcServerConfig,
    interceptors: List[ServerInterceptor],
    trafficQuota: Option[TrafficQuotaBlobService.Dependencies],
  ): ZIO[Scope, Throwable, Server] =
    val builder     = NettyServerBuilder
      .forPort(config.port)
      .maxInboundMessageSize(config.maxInboundMessageBytes)
    interceptors match
      case Nil    => ()
      case values =>
        val _ = builder.intercept(compose(values))
    val baseService = TrafficQuotaBlobService.contextual(new BlobServiceImpl(blobStore, uploadIngestor))
    val blobService = trafficQuota.fold(baseService) { dependencies =>
      new TrafficQuotaBlobService(baseService, dependencies.quota, dependencies.metrics)
    }
    ScopedServer.fromServices(
      builder,
      blobService,
      new AdminServiceImpl(blobStore),
    )

  def scopedTenants(
    fallbackStore: BlobStore,
    provider: TenantStoreProvider,
    tenantContext: TenantContext,
    ingestorFor: BlobStore => UploadIngestor,
    config: GrpcServerConfig,
    interceptors: List[ServerInterceptor],
  ): ZIO[Scope, Throwable, Server] =
    scopedTenants(fallbackStore, provider, tenantContext, ingestorFor, config, interceptors, None)

  def scopedTenants(
    fallbackStore: BlobStore,
    provider: TenantStoreProvider,
    tenantContext: TenantContext,
    ingestorFor: BlobStore => UploadIngestor,
    config: GrpcServerConfig,
    interceptors: List[ServerInterceptor],
    trafficQuota: Option[TrafficQuotaBlobService.Dependencies],
  ): ZIO[Scope, Throwable, Server] =
    val builder     = NettyServerBuilder
      .forPort(config.port)
      .maxInboundMessageSize(config.maxInboundMessageBytes)
    interceptors match
      case Nil    => ()
      case values =>
        val _ = builder.intercept(compose(values))
    val baseService = new TenantBlobServiceImpl(provider, tenantContext, ingestorFor)
    val blobService = trafficQuota.fold(baseService: io.graviton.blobstore.v1.blob_service.ZioBlobService.RCBlobService) { dependencies =>
      new TrafficQuotaBlobService(baseService, dependencies.quota, dependencies.metrics)
    }
    ScopedServer.fromServices(
      builder,
      blobService,
      new AdminServiceImpl(fallbackStore),
    )

  def serve(blobStore: BlobStore, config: GrpcServerConfig = GrpcServerConfig()): ZIO[Any, Throwable, Nothing] =
    ZIO.scoped(scoped(blobStore, config).flatMap(_.awaitTermination)).forever

  private def compose(values: List[ServerInterceptor]): ServerInterceptor =
    new ServerInterceptor:
      override def interceptCall[ReqT, RespT](
        call: ServerCall[ReqT, RespT],
        headers: io.grpc.Metadata,
        next: ServerCallHandler[ReqT, RespT],
      ): ServerCall.Listener[ReqT] =
        val chain = values.foldRight(next) { (interceptor, downstream) =>
          new ServerCallHandler[ReqT, RespT]:
            override def startCall(
              currentCall: ServerCall[ReqT, RespT],
              currentHeaders: io.grpc.Metadata,
            ): ServerCall.Listener[ReqT] =
              interceptor.interceptCall(currentCall, currentHeaders, downstream)
        }
        chain.startCall(call, headers)
