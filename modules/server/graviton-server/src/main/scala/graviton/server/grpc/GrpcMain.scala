package graviton.server.grpc

import graviton.protocol.grpc.{GravitonGrpcServer, GrpcServerConfig}
import graviton.runtime.stores.BlobStore
import zio.ZIO

object GrpcMain:
  def run(blobStore: BlobStore, port: Int): ZIO[Any, Throwable, Nothing] =
    GravitonGrpcServer.serve(blobStore, GrpcServerConfig(port))
