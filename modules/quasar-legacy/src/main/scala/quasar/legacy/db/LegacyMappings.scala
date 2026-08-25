package quasar.legacy.db

import graviton.core.keys.BinaryKey
import zio.*

import java.util.UUID

final case class LegacyDocRef(repo: String, docId: String)

final case class LegacyBinaryRef(repo: String, binaryHash: String)

type BlobKey = BinaryKey.Blob

enum LegacyImportStatus derives CanEqual:
  case importing, imported, failed

trait LegacyMappings:
  def lookupDoc(orgId: UUID, ref: LegacyDocRef): IO[Throwable, Option[(UUID, LegacyImportStatus)]]
  def upsertDoc(orgId: UUID, ref: LegacyDocRef, documentId: UUID, status: LegacyImportStatus): IO[Throwable, Unit]

  def lookupBinary(orgId: UUID, ref: LegacyBinaryRef): IO[Throwable, Option[BlobKey]]
  def upsertBinary(orgId: UUID, ref: LegacyBinaryRef, blob: BlobKey): IO[Throwable, Unit]
