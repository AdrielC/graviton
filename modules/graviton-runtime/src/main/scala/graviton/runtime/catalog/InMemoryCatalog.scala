package graviton.runtime.catalog

import graviton.core.attributes.IngestStats
import graviton.core.keys.BinaryKey
import zio.*
import zio.blocks.mediatype.MediaType

/** Process-local catalog for the zero-configuration filesystem runtime. */
final class InMemoryCatalog private (state: Ref.Synchronized[InMemoryCatalog.State]) extends Catalog:

  override def list(folderId: Option[CatalogFolderId]): IO[CatalogError, CatalogListing] =
    state.get.flatMap { current =>
      val selected = folderId.flatMap(current.folders.get)
      folderId match
        case Some(id) if selected.isEmpty => ZIO.fail(CatalogError.NotFound("folder", id.value.toString))
        case _                            =>
          val children = Chunk.fromIterable(current.folders.values.filter(_.parent == folderId).toSeq.sortBy(_.name.value.toLowerCase))
          val files    = Chunk.fromIterable(current.files.values.filter(_.folder == folderId).toSeq.sortBy(_.name.value.toLowerCase))
          ZIO.succeed(CatalogListing(selected, breadcrumbs(selected, current.folders), children, files))
    }

  override def createFolder(
    parent: Option[CatalogFolderId],
    name: CatalogName,
  ): IO[CatalogError, CatalogFolder] =
    for
      id    <- CatalogFolderId.fresh
      now   <- Clock.instant
      folder = CatalogFolder(id, parent, name, now)
      _     <- state.modifyZIO { current =>
                 if parent.exists(id => !current.folders.contains(id)) then ZIO.fail(CatalogError.NotFound("folder", parent.get.value.toString))
                 else if current.nameExists(parent, name) then ZIO.fail(CatalogError.NameConflict(name))
                 else ZIO.succeed(((), current.copy(folders = current.folders.updated(id, folder))))
               }
    yield folder

  override def getFile(id: CatalogFileId): IO[CatalogError, CatalogFile] =
    state.get.flatMap(current => ZIO.fromOption(current.files.get(id)).orElseFail(CatalogError.NotFound("file", id.value.toString)))

  override def attachFile(
    folder: Option[CatalogFolderId],
    name: CatalogName,
    blob: BinaryKey.Blob,
    mediaType: MediaType,
    stats: IngestStats,
  ): IO[CatalogError, CatalogFile] =
    for
      id  <- CatalogFileId.fresh
      now <- Clock.instant
      file = CatalogFile(id, folder, name, blob, mediaType, stats.blockCount, stats.freshBlocks, stats.duplicateBlocks, now)
      _   <- state.modifyZIO { current =>
               if folder.exists(id => !current.folders.contains(id)) then ZIO.fail(CatalogError.NotFound("folder", folder.get.value.toString))
               else if current.nameExists(folder, name) then ZIO.fail(CatalogError.NameConflict(name))
               else ZIO.succeed(((), current.copy(files = current.files.updated(id, file))))
             }
    yield file

  override def removeFile(id: CatalogFileId): IO[CatalogError, Unit] =
    state.modifyZIO { current =>
      if current.files.contains(id) then ZIO.succeed(((), current.copy(files = current.files.removed(id))))
      else ZIO.fail(CatalogError.NotFound("file", id.value.toString))
    }

  override def removeFolder(id: CatalogFolderId): IO[CatalogError, Unit] =
    state.modifyZIO { current =>
      if !current.folders.contains(id) then ZIO.fail(CatalogError.NotFound("folder", id.value.toString))
      else if current.folders.values.exists(_.parent.contains(id)) || current.files.values.exists(_.folder.contains(id)) then
        ZIO.fail(CatalogError.FolderNotEmpty(id))
      else ZIO.succeed(((), current.copy(folders = current.folders.removed(id))))
    }

  private def breadcrumbs(
    folder: Option[CatalogFolder],
    folders: Map[CatalogFolderId, CatalogFolder],
  ): Chunk[CatalogFolder] =
    @annotation.tailrec
    def loop(current: Option[CatalogFolder], result: List[CatalogFolder]): List[CatalogFolder] =
      current match
        case None        => result
        case Some(value) => loop(value.parent.flatMap(folders.get), value :: result)
    Chunk.fromIterable(loop(folder, Nil))

object InMemoryCatalog:
  private final case class State(
    folders: Map[CatalogFolderId, CatalogFolder],
    files: Map[CatalogFileId, CatalogFile],
  ):
    def nameExists(parent: Option[CatalogFolderId], name: CatalogName): Boolean =
      val normalized = name.value.toLowerCase(java.util.Locale.ROOT)
      folders.values.exists(folder => folder.parent == parent && folder.name.value.toLowerCase(java.util.Locale.ROOT) == normalized) ||
      files.values.exists(file => file.folder == parent && file.name.value.toLowerCase(java.util.Locale.ROOT) == normalized)

  val layer: ULayer[Catalog] =
    ZLayer.fromZIO(Ref.Synchronized.make(State(Map.empty, Map.empty)).map(new InMemoryCatalog(_)))
