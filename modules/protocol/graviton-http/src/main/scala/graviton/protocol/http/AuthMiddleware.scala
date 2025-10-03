package graviton.protocol.http

import zio.http.{Handler, Request, Response}

object AuthMiddleware:
  def optional(handler: Handler[Any, Throwable, Request, Response]): Handler[Any, Throwable, Request, Response] = handler
