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
                    case Some((docId, LegacyImportStatus.imported)) =>
                      // If needed, callers can resolve blob via a later lookup path.
                      ZIO.succeed(ImportOutcome(docId, BlobKey("unknown")))
                    case _                                          =>
                      importFresh(orgId, repo, legacyDocId)
    yield out

  private def importFresh(
    orgId: UUID,
    repo: String,
    legacyDocId: String,
  ): IO[Throwable, ImportOutcome] =
    for
      desc    <- catalog.resolve(LegacyId(repo, legacyDocId))
      binRef   = LegacyBinaryRef(repo, desc.binaryHash)
      blobKey <- mappings.lookupBinary(orgId, binRef).flatMap {
                   case Some(found) => ZIO.succeed(found)
                   case None        => ingestBinary(orgId, binRef)
                 }
      docId   <- ZIO.succeed(UUID.randomUUID())
      _       <- mappings.upsertDoc(orgId, LegacyDocRef(repo, legacyDocId), docId, LegacyImportStatus.imported)
    yield ImportOutcome(docId, blobKey)

  private def ingestBinary(orgId: UUID, ref: LegacyBinaryRef): IO[Throwable, BlobKey] =
    for
      result <- fs.open(ref.repo, ref.binaryHash).run(blobStore.put())
      // We intentionally do not derive a "document id" from legacy ids. This blob key is CAS-derived.
      key     = BlobKey(result.key.bits.digest.hex.value)
      _      <- mappings.upsertBinary(orgId, ref, key)
    yield key

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
