package graviton.server.http

import zio.{Scope, ZIO}

object HttpMain:
  val run: ZIO[Scope, Throwable, Unit] = ZIO.logInfo("Starting HTTP server")
