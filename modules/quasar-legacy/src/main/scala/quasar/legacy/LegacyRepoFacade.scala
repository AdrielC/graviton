package quasar.legacy

import quasar.legacy.service.LegacyImportService
import quasar.core.{ContentRef, DocumentId}
import zio.*

/** Compatibility facade over the operational, idempotent legacy import service. */
trait LegacyRepoFacade:
  def getOrImport(repo: String, docId: String): IO[Throwable, (DocumentId, ContentRef)]

object LegacyRepoFacade:
  def live(service: LegacyImportService): ULayer[LegacyRepoFacade] =
    ZLayer.succeed {
      new LegacyRepoFacade {
        override def getOrImport(repo: String, docId: String): IO[Throwable, (DocumentId, ContentRef)] =
          service.importIfNeeded(repo, docId).map { outcome =>
            DocumentId(outcome.documentId) -> ContentRef(quasar.core.ContentKind.blob, outcome.blobKey.bits.render)
          }
      }
    }
