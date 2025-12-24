package quasar.legacy

import graviton.runtime.legacy.{LegacyCatalog, LegacyFs, LegacyId}
import quasar.core.{ContentRef, DocumentId}
import zio.*

/**
 * Placeholder for Quasar-side "legacy repo" integration.
 *
 * The storage backend remains CAS-only in Graviton; Quasar is responsible for
 * mapping legacy ids to Quasar documents and choosing whether to read-through or import-on-read.
 */
trait LegacyRepoFacade:
  def getOrImport(repo: String, docId: String): IO[Throwable, (DocumentId, ContentRef)]

object LegacyRepoFacade:
  def live(catalog: LegacyCatalog, fs: LegacyFs): ULayer[LegacyRepoFacade] =
    ZLayer.succeed {
      new LegacyRepoFacade {
        override def getOrImport(repo: String, docId: String): IO[Throwable, (DocumentId, ContentRef)] =
          // Scaffold only: the real implementation will write mappings + versions.
          catalog.resolve(LegacyId(repo, docId)).flatMap { _ =>
            ZIO.fail(new UnsupportedOperationException("LegacyRepoFacade.getOrImport not implemented"))
          }
      }
    }
