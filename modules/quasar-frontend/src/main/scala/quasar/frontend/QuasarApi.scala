package quasar.frontend

import zio.*
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

final case class QuasarApi(
  baseUrl: String,
  http: BrowserHttpClient,
) {

  def health: UIO[Boolean] =
    http.get("/v1/health").as(true).catchAll(_ => ZIO.succeed(false))

  def legacyImport(legacyRepo: String, legacyDocId: String): Task[LegacyImportResponse] =
    http
      .post(
        "/v1/legacy/import",
        LegacyImportRequest(legacyRepo = legacyRepo, legacyDocId = legacyDocId).toJson,
      )
      .flatMap { raw =>
        raw.fromJson[LegacyImportResponse] match
          case Left(err)   => ZIO.fail(new Exception(err))
          case Right(resp) => ZIO.succeed(resp)
      }
}
