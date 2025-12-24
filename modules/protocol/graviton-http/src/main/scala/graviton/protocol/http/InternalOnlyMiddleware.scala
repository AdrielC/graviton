package graviton.protocol.http

import zio.*
import zio.http.*

/**
 * Minimal "internal-only" guardrail:
 * - not meant for public clients
 * - token check is explicit and easy to wire behind a separate listener
 *
 * Prefer mTLS in real deployments; this is the v1 minimum for avoiding accidental exposure.
 */
object InternalOnlyMiddleware:

  private val HeaderName = "x-internal-token"

  def requireToken(expected: String)(handler: Handler[Any, Nothing, Request, Response]): Handler[Any, Nothing, Request, Response] =
    Handler.fromFunctionZIO[Request] { req =>
      val provided = req.rawHeader(HeaderName)
      if provided.contains(expected) then ZIO.scoped(handler(req))
      else ZIO.succeed(Response.status(Status.Unauthorized))
    }
