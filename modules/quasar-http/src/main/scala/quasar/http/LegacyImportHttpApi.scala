package quasar.http

import graviton.streams.BoundedByteStream
import quasar.legacy.service.LegacyImportService
import zio.*
import zio.http.*
import zio.json.*

import java.util.UUID
import java.nio.charset.StandardCharsets

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
      BoundedByteStream
        .collectControlPlane(req.body.asStream)
        .map(bytes => new String(bytes.toArray, StandardCharsets.UTF_8))
        .foldZIO(
          _ => ZIO.succeed(Response.status(Status.RequestEntityTooLarge)),
          body => {
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
                  .map(out => LegacyImportResponse(out.documentId, out.blobKey.bits.render))
                  .map(resp => Response.json(resp.toJson))
                  .catchAll(th =>
                    ZIO.succeed(
                      Response(
                        status = Status.InternalServerError,
                        body = Body.fromString(Option(th.getMessage).getOrElse("internal error")),
                      )
                    )
                  )
          },
        )
    }

  val routes: Routes[Any, Nothing] =
    Routes(
      Method.POST / "v1" / "legacy" / "import" -> handler
    )
