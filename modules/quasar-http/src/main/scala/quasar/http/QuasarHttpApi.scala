package quasar.http

import zio.*
import zio.http.*

/** Liveness and optional operational legacy-import routes for the Quasar process. */
final case class QuasarHttpApi(
  legacyImport: Option[LegacyImportHttpApi] = None
):

  val routes: Routes[Any, Nothing] =
    Routes(
      Method.GET / "v1" / "health" -> Handler.succeed(Response.text("ok"))
    ) ++ legacyImport.map(_.routes).getOrElse(Routes.empty)
