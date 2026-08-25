package quasar.legacy.service

import graviton.runtime.legacy.{LegacyCatalog, LegacyFs, LegacyId}
import graviton.runtime.stores.BlobStore
import quasar.legacy.db.*
import zio.*

import java.util.UUID

final case class ImportOutcome(documentId: UUID, blobKey: BlobKey)

/**
 * Import-on-read:
 * - idempotent by (org_id, legacy_repo, legacy_doc_id)
 * - dedupe by (org_id, legacy_repo, legacy_binary_hash) -> blob_key
 */
final class LegacyImportService(
  tenant: TenantContext,
  mappings: LegacyMappings,
  catalog: LegacyCatalog,
  fs: LegacyFs,
  blobStore: BlobStore,
):

  def importIfNeeded(repo: String, legacyDocId: String): IO[Throwable, ImportOutcome] =
    for
      orgId    <- tenant.orgId
      ref       = LegacyDocRef(repo, legacyDocId)
      existing <- mappings.lookupDoc(orgId, ref)
      out      <- existing match
                    case Some((docId, LegacyImportStatus.imported)) => resolveImported(orgId, ref, docId)
                    case Some((docId, _))                           => importFresh(orgId, repo, legacyDocId, Some(docId))
                    case None                                       => importFresh(orgId, repo, legacyDocId, None)
    yield out

  private def importFresh(
    orgId: UUID,
    repo: String,
    legacyDocId: String,
    existingDocumentId: Option[UUID],
  ): IO[Throwable, ImportOutcome] =
    for
      desc    <- catalog.resolve(LegacyId(repo, legacyDocId))
      binRef   = LegacyBinaryRef(repo, desc.binaryHash)
      blobKey <- mappings.lookupBinary(orgId, binRef).flatMap {
                   case Some(found) => ZIO.succeed(found)
                   case None        => ingestBinary(orgId, binRef)
                 }
      docId   <- ZIO.succeed(existingDocumentId.getOrElse(UUID.randomUUID()))
      _       <- mappings.upsertDoc(orgId, LegacyDocRef(repo, legacyDocId), docId, LegacyImportStatus.imported)
    yield ImportOutcome(docId, blobKey)

  private def ingestBinary(orgId: UUID, ref: LegacyBinaryRef): IO[Throwable, BlobKey] =
    for
      result <- fs.open(ref.repo, ref.binaryHash).run(blobStore.put())
      // We intentionally do not derive a "document id" from legacy ids. This blob key is CAS-derived.
      key     = result.key
      _      <- mappings.upsertBinary(orgId, ref, key)
    yield key

  private def resolveImported(orgId: UUID, ref: LegacyDocRef, documentId: UUID): IO[Throwable, ImportOutcome] =
    for
      desc <- catalog.resolve(LegacyId(ref.repo, ref.docId))
      key  <- mappings
                .lookupBinary(orgId, LegacyBinaryRef(ref.repo, desc.binaryHash))
                .someOrFail(new IllegalStateException(s"Imported legacy document '${ref.repo}/${ref.docId}' has no blob mapping"))
    yield ImportOutcome(documentId, key)

  // NOTE: creating quasar.document/quasar.document_version is intentionally deferred.
  // The current repository does not yet have a stable, tenant-implicit service layer for
  // auth (principal), permissions, labels, and versioning semantics.

object LegacyImportService:
  val layer: ZLayer[
    TenantContext & LegacyMappings & LegacyCatalog & LegacyFs & BlobStore,
    Nothing,
    LegacyImportService,
  ] =
    ZLayer.fromFunction(new LegacyImportService(_, _, _, _, _))
