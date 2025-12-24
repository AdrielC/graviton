package graviton.protocol.http

import zio.http.*

/**
 * Internal-only HTTP surface.
 *
 * Rules:
 * - Runs on a separate listener/port.
 * - Guarded by an explicit internal token (or mTLS in production).
 * - Default off in deployment wiring.
 */
final case class InternalHttpApi(
  token: String,
  legacyRepo: LegacyRepoHttpApi,
):

  private val guardedLegacyHandler: Handler[Any, Nothing, (String, String, Request), Response] =
    Handler.fromFunctionZIO[(String, String, Request)] { case (repo, docId, req) =>
      val provided = req.rawHeader("x-internal-token")
      if provided.contains(token) then legacyRepo.getLegacyHandler((repo, docId, req))
      else zio.ZIO.succeed(Response.status(Status.Unauthorized))
    }

  private val guardedLegacy: Routes[Any, Nothing] =
    Routes(
      Method.GET / "internal" / "legacy" / string("repo") / string("docId") -> guardedLegacyHandler
    )

  val routes: Routes[Any, Nothing] = guardedLegacy
