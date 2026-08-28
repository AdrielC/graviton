package graviton.protocol.grpc

import graviton.runtime.stores.BlobStore
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
    scoped(blobStore, UploadIngestor.default(blobStore), config, interceptors)

  def scoped(
    blobStore: BlobStore,
    uploadIngestor: UploadIngestor,
    config: GrpcServerConfig,
    interceptors: List[ServerInterceptor],
  ): ZIO[Scope, Throwable, Server] =
    val builder = NettyServerBuilder
      .forPort(config.port)
      .maxInboundMessageSize(config.maxInboundMessageBytes)
    interceptors match
      case Nil    => ()
      case values =>
        val _ = builder.intercept(compose(values))
    ScopedServer.fromServices(
      builder,
      new BlobServiceImpl(blobStore, uploadIngestor),
      new AdminServiceImpl(blobStore),
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
