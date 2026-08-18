package graviton.protocol.grpc

import zio.ZIO

final case class AdminServiceImpl(status: String = "ok"):
  def health: ZIO[Any, Nothing, String] = ZIO.succeed(status)
