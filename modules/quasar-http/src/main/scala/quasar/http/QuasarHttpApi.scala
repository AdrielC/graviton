package quasar.http

import zio.*
import zio.http.*

/**
 * Placeholder Quasar HTTP surface.
 *
 * The repository currently has a Graviton-focused server and protocol stack; this module
 * is the landing spot for the tenant-implicit document API described in docs.
 */
final case class QuasarHttpApi(
  legacyImport: Option[LegacyImportHttpApi] = None,
):

  val routes: Routes[Any, Nothing] =
    Routes(
      Method.GET / "v1" / "health" -> Handler.succeed(Response.text("ok"))
    ) ++ legacyImport.map(_.routes).getOrElse(Routes.empty)
