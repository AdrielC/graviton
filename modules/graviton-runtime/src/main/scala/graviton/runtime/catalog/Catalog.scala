package graviton.runtime.catalog

import graviton.core.attributes.IngestStats
import graviton.core.keys.BinaryKey
import zio.*
import zio.blocks.mediatype.MediaType

import java.time.Instant
import java.util.UUID

/** A validated identifier for a mutable folder in the catalog. */
final case class CatalogFolderId private (value: UUID)

object CatalogFolderId:
  def fresh: UIO[CatalogFolderId] = Random.nextUUID.map(CatalogFolderId(_))

  def parse(value: String): Either[String, CatalogFolderId] =
    scala.util.Try(UUID.fromString(value)).toEither.left.map(_ => "folder ID must be a UUID").map(CatalogFolderId(_))

/** A validated identifier for a mutable file reference in the catalog. */
final case class CatalogFileId private (value: UUID)

object CatalogFileId:
  def fresh: UIO[CatalogFileId] = Random.nextUUID.map(CatalogFileId(_))

  def parse(value: String): Either[String, CatalogFileId] =
    scala.util.Try(UUID.fromString(value)).toEither.left.map(_ => "file ID must be a UUID").map(CatalogFileId(_))

/**
 * A user-visible catalog name.
 *
 * Names are trimmed once at the boundary and cannot contain path separators or
 * control characters. Folder structure is represented by typed IDs, never by
 * parsing a user-supplied path string.
 */
final case class CatalogName private (value: String)

object CatalogName:
  val MaxLength = 255

  def parse(value: String): Either[String, CatalogName] =
    val normalized = value.trim
    Either.cond(
      normalized.nonEmpty &&
        normalized.length <= MaxLength &&
        !normalized.exists(character => character == '/' || character == '\\' || Character.isISOControl(character)),
      CatalogName(normalized),
      s"name must contain 1..$MaxLength visible characters and no path separators",
    )

final case class CatalogFolder(
  id: CatalogFolderId,
  parent: Option[CatalogFolderId],
  name: CatalogName,
  createdAt: Instant,
)

/** Mutable name and folder placement pointing at immutable CAS content. */
final case class CatalogFile(
  id: CatalogFileId,
  folder: Option[CatalogFolderId],
  name: CatalogName,
  blob: BinaryKey.Blob,
  mediaType: MediaType,
  blockCount: Int,
  freshBlocks: Int,
  duplicateBlocks: Int,
  createdAt: Instant,
)

final case class CatalogListing(
  folder: Option[CatalogFolder],
  breadcrumbs: Chunk[CatalogFolder],
  folders: Chunk[CatalogFolder],
  files: Chunk[CatalogFile],
)

sealed trait CatalogError extends Exception

object CatalogError:
  final case class InvalidInput(message: String)       extends Exception(message) with CatalogError
  final case class NotFound(kind: String, id: String)  extends Exception(s"$kind not found: $id") with CatalogError
  final case class NameConflict(name: CatalogName)     extends Exception(s"An entry named '${name.value}' already exists") with CatalogError
  final case class FolderNotEmpty(id: CatalogFolderId) extends Exception(s"Folder is not empty: ${id.value}") with CatalogError
  final case class Storage(operation: String, cause: Throwable)
      extends Exception(s"Catalog $operation failed: ${Option(cause.getMessage).getOrElse(cause.getClass.getSimpleName)}", cause)
      with CatalogError

/**
 * Mutable organization for immutable CAS blobs.
 *
 * Removing a file here removes only its catalog reference. Blob manifests and
 * blocks stay under the CAS retention and maintenance policy.
 */
trait Catalog:
  def list(folder: Option[CatalogFolderId]): IO[CatalogError, CatalogListing]
  def getFile(id: CatalogFileId): IO[CatalogError, CatalogFile]
  def createFolder(parent: Option[CatalogFolderId], name: CatalogName): IO[CatalogError, CatalogFolder]
  def attachFile(
    folder: Option[CatalogFolderId],
    name: CatalogName,
    blob: BinaryKey.Blob,
    mediaType: MediaType,
    stats: IngestStats,
  ): IO[CatalogError, CatalogFile]
  def removeFile(id: CatalogFileId): IO[CatalogError, Unit]
  def removeFolder(id: CatalogFolderId): IO[CatalogError, Unit]

object Catalog:
  def list(folder: Option[CatalogFolderId]): ZIO[Catalog, CatalogError, CatalogListing] =
    ZIO.serviceWithZIO[Catalog](_.list(folder))

  def createFolder(parent: Option[CatalogFolderId], name: CatalogName): ZIO[Catalog, CatalogError, CatalogFolder] =
    ZIO.serviceWithZIO[Catalog](_.createFolder(parent, name))

  def getFile(id: CatalogFileId): ZIO[Catalog, CatalogError, CatalogFile] =
    ZIO.serviceWithZIO[Catalog](_.getFile(id))

  def attachFile(
    folder: Option[CatalogFolderId],
    name: CatalogName,
    blob: BinaryKey.Blob,
    mediaType: MediaType,
    stats: IngestStats,
  ): ZIO[Catalog, CatalogError, CatalogFile] =
    ZIO.serviceWithZIO[Catalog](_.attachFile(folder, name, blob, mediaType, stats))

  def removeFile(id: CatalogFileId): ZIO[Catalog, CatalogError, Unit] =
    ZIO.serviceWithZIO[Catalog](_.removeFile(id))

  def removeFolder(id: CatalogFolderId): ZIO[Catalog, CatalogError, Unit] =
    ZIO.serviceWithZIO[Catalog](_.removeFolder(id))
