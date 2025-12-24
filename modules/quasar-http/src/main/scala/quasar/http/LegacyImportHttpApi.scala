package quasar.http

import quasar.legacy.service.LegacyImportService
import zio.*
import zio.http.*
import zio.json.*

import java.util.UUID

final case class LegacyImportRequest(
  legacyRepo: String,
  legacyDocId: String,
  mode: Option[String] = None,
) derives JsonCodec

final case class LegacyImportResponse(
  documentId: UUID,
  blobKey: String,
) derives JsonCodec

final case class LegacyImportHttpApi(service: LegacyImportService):

  private val handler: Handler[Any, Nothing, Request, Response] =
    Handler.fromFunctionZIO[Request] { req =>
      req.body.asString.catchAll(_ => ZIO.succeed("")).flatMap { body =>
        body.fromJson[LegacyImportRequest] match
          case Left(err)     =>
            ZIO.succeed(
              Response(
                status = Status.BadRequest,
                body = Body.fromString(err),
              )
            )
          case Right(parsed) =>
            service
              .importIfNeeded(parsed.legacyRepo, parsed.legacyDocId)
              .map(out => LegacyImportResponse(out.documentId, out.blobKey.value))
              .map(resp => Response.json(resp.toJson))
              .catchAll(th =>
                ZIO.succeed(
                  Response(
                    status = Status.InternalServerError,
                    body = Body.fromString(Option(th.getMessage).getOrElse("internal error")),
                  )
                )
              )
      }
    }

  val routes: Routes[Any, Nothing] =
    Routes(
      Method.POST / "v1" / "legacy" / "import" -> handler
    )
