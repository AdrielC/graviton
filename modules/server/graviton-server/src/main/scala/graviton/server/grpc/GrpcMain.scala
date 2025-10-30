package graviton.server.grpc

import zio.{Scope, ZIO}

object GrpcMain:
  val run: ZIO[Scope, Throwable, Unit] =
    ZIO.logInfo("Starting gRPC server") *> ZIO.unit
