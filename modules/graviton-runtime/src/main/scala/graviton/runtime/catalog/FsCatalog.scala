package graviton.runtime.catalog

import graviton.core.attributes.IngestStats
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.shared.MediaTypeText
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.MaxLength
import zio.*
import zio.blocks.mediatype.MediaType
import zio.blocks.schema.*

import java.io.{FilterOutputStream, OutputStream, OutputStreamWriter}
import java.nio.channels.{Channels, FileChannel}
import java.nio.charset.StandardCharsets
import java.nio.file.StandardCopyOption.{ATOMIC_MOVE, REPLACE_EXISTING}
import java.nio.file.StandardOpenOption.{CREATE, TRUNCATE_EXISTING, WRITE}
import java.nio.file.{AtomicMoveNotSupportedException, Files, Path}
import java.time.Instant

/**
 * Restart-safe catalog for the single-node filesystem runtime.
 *
 * Catalog input is read into a compile-time-refined byte buffer only after its
 * runtime ceiling is enforced. Output is encoded through a bounded stream and
 * replaced atomically after each mutation. Immutable blob bytes remain under
 * the CAS retention policy and are never copied into this catalog.
 */
final class FsCatalog private (path: Path, state: Ref.Synchronized[FsCatalog.State]) extends Catalog:
  import FsCatalog.*

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

  override def getFile(id: CatalogFileId): IO[CatalogError, CatalogFile] =
    state.get.flatMap(current => ZIO.fromOption(current.files.get(id)).orElseFail(CatalogError.NotFound("file", id.value.toString)))

  override def createFolder(parent: Option[CatalogFolderId], name: CatalogName): IO[CatalogError, CatalogFolder] =
    for
      id     <- CatalogFolderId.fresh
      now    <- Clock.instant
      folder  = CatalogFolder(id, parent, name, now)
      result <- mutate("create folder") { current =>
                  if parent.exists(id => !current.folders.contains(id)) then
                    Left(CatalogError.NotFound("folder", parent.get.value.toString))
                  else if current.nameExists(parent, name) then Left(CatalogError.NameConflict(name))
                  else Right(folder -> current.copy(folders = current.folders.updated(id, folder)))
                }
    yield result

  override def attachFile(
    folder: Option[CatalogFolderId],
    name: CatalogName,
    blob: BinaryKey.Blob,
    mediaType: MediaType,
    stats: IngestStats,
  ): IO[CatalogError, CatalogFile] =
    for
      id    <- CatalogFileId.fresh
      now   <- Clock.instant
      file   = CatalogFile(id, folder, name, blob, mediaType, stats.blockCount, stats.freshBlocks, stats.duplicateBlocks, now)
      saved <- mutate("attach file") { current =>
                 if folder.exists(id => !current.folders.contains(id)) then Left(CatalogError.NotFound("folder", folder.get.value.toString))
                 else if current.nameExists(folder, name) then Left(CatalogError.NameConflict(name))
                 else Right(file -> current.copy(files = current.files.updated(id, file)))
               }
    yield saved

  override def removeFile(id: CatalogFileId): IO[CatalogError, Unit] =
    mutate("remove file") { current =>
      if current.files.contains(id) then Right(() -> current.copy(files = current.files.removed(id)))
      else Left(CatalogError.NotFound("file", id.value.toString))
    }

  override def removeFolder(id: CatalogFolderId): IO[CatalogError, Unit] =
    mutate("remove folder") { current =>
      if !current.folders.contains(id) then Left(CatalogError.NotFound("folder", id.value.toString))
      else if current.folders.values.exists(_.parent.contains(id)) || current.files.values.exists(_.folder.contains(id)) then
        Left(CatalogError.FolderNotEmpty(id))
      else Right(() -> current.copy(folders = current.folders.removed(id)))
    }

  private def mutate[A](operation: String)(f: State => Either[CatalogError, (A, State)]): IO[CatalogError, A] =
    state.modifyZIO { current =>
      ZIO.fromEither(f(current)).flatMap { case (result, next) =>
        persist(path, next)
          .mapError(CatalogError.Storage(operation, _))
          .as(result -> next)
      }
    }

object FsCatalog:
  private val Version = 1

  private type CatalogBytes = Array[Byte] :| MaxLength[16777216]

  private[catalog] val MaxCatalogBytes = 16 * 1024 * 1024

  private final case class DiskFolder(id: String, parent: Option[String], name: String, createdAt: String)
  private object DiskFolder:
    given Schema[DiskFolder] = Schema.derived

  private final case class DiskFile(
    id: String,
    folder: Option[String],
    name: String,
    blob: String,
    mediaType: String,
    blockCount: Int,
    freshBlocks: Int,
    duplicateBlocks: Int,
    createdAt: String,
  )
  private object DiskFile:
    given Schema[DiskFile] = Schema.derived

  private final case class DiskState(version: Int, folders: List[DiskFolder], files: List[DiskFile])
  private object DiskState:
    given Schema[DiskState] = Schema.derived

  private final case class State(
    folders: Map[CatalogFolderId, CatalogFolder],
    files: Map[CatalogFileId, CatalogFile],
  ):
    def nameExists(parent: Option[CatalogFolderId], name: CatalogName): Boolean =
      val normalized = name.value.toLowerCase(java.util.Locale.ROOT)
      folders.values.exists(folder => folder.parent == parent && folder.name.value.toLowerCase(java.util.Locale.ROOT) == normalized) ||
      files.values.exists(file => file.folder == parent && file.name.value.toLowerCase(java.util.Locale.ROOT) == normalized)

  def layer(root: Path): TaskLayer[Catalog] =
    ZLayer.fromZIO {
      val path = root.resolve("catalog").resolve("catalog-v1.json")
      for
        initial <- load(path)
        ref     <- Ref.Synchronized.make(initial)
      yield new FsCatalog(path, ref)
    }

  private def load(path: Path): Task[State] =
    ZIO.attemptBlocking {
      if !Files.exists(path) then State(Map.empty, Map.empty)
      else
        val size  = Files.size(path)
        if size > MaxCatalogBytes.toLong then
          throw new IllegalStateException(s"Catalog exceeds the $MaxCatalogBytes-byte metadata bound: $path")
        val bytes = readBounded(path)
        val disk  = new String(bytes, StandardCharsets.UTF_8)
          .fromJson[DiskState]
          .fold(error => throw new IllegalStateException(s"Catalog is invalid: $error"), identity)
        decode(disk).fold(error => throw new IllegalStateException(s"Catalog is invalid: $error"), identity)
    }

  private def persist(path: Path, state: State): Task[Unit] =
    ZIO.attemptBlocking {
      val json   = encode(state).toJsonString
      val parent = path.getParent
      Files.createDirectories(parent)
      val temp   = Files.createTempFile(parent, ".catalog-v1-", ".tmp")
      try
        val channel = FileChannel.open(temp, CREATE, WRITE, TRUNCATE_EXISTING)
        try
          val writer = new OutputStreamWriter(
            BoundedOutputStream(Channels.newOutputStream(channel), MaxCatalogBytes.toLong),
            StandardCharsets.UTF_8,
          )
          try
            writer.write(json)
            writer.flush()
            channel.force(true)
          finally writer.close()
        finally channel.close()
        try Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
        catch case _: AtomicMoveNotSupportedException => Files.move(temp, path, REPLACE_EXISTING)
      finally
        Files.deleteIfExists(temp)
        ()
    }.unit

  private def readBounded(path: Path): CatalogBytes =
    val input = Files.newInputStream(path)
    try
      val bytes = input.readNBytes(MaxCatalogBytes + 1)
      if bytes.length > MaxCatalogBytes then
        throw new IllegalStateException(s"Catalog exceeds the $MaxCatalogBytes-byte metadata bound: $path")
      bytes
        .refineEither[MaxLength[16777216]]
        .fold(message => throw new IllegalStateException(message), identity)
    finally input.close()

  private final class BoundedOutputStream private (delegate: OutputStream, limit: Long) extends FilterOutputStream(delegate):
    private var written = 0L

    private def reserve(length: Int): Unit =
      val next = java.lang.Math.addExact(written, length.toLong)
      if next > limit then throw new IllegalStateException(s"Catalog exceeds the $limit-byte metadata bound")
      written = next

    override def write(value: Int): Unit =
      reserve(1)
      out.write(value)

    override def write(bytes: Array[Byte], offset: Int, length: Int): Unit =
      reserve(length)
      out.write(bytes, offset, length)

  private object BoundedOutputStream:
    def apply(delegate: OutputStream, limit: Long): BoundedOutputStream = new BoundedOutputStream(delegate, limit)

  private def encode(state: State): DiskState =
    DiskState(
      Version,
      state.folders.values.toList.sortBy(_.id.value.toString).map { value =>
        DiskFolder(value.id.value.toString, value.parent.map(_.value.toString), value.name.value, value.createdAt.toString)
      },
      state.files.values.toList.sortBy(_.id.value.toString).map { value =>
        DiskFile(
          value.id.value.toString,
          value.folder.map(_.value.toString),
          value.name.value,
          value.blob.bits.render,
          MediaTypeText.render(value.mediaType),
          value.blockCount,
          value.freshBlocks,
          value.duplicateBlocks,
          value.createdAt.toString,
        )
      },
    )

  private def decode(disk: DiskState): Either[String, State] =
    for
      _       <- Either.cond(disk.version == Version, (), s"unsupported version ${disk.version}")
      folders <- disk.folders.foldLeft[Either[String, Map[CatalogFolderId, CatalogFolder]]](Right(Map.empty)) { (result, value) =>
                   for
                     current <- result
                     id      <- CatalogFolderId.parse(value.id)
                     parent  <-
                       value.parent.map(CatalogFolderId.parse).fold[Either[String, Option[CatalogFolderId]]](Right(None))(_.map(Some(_)))
                     name    <- CatalogName.parse(value.name)
                     created <- scala.util
                                  .Try(Instant.parse(value.createdAt))
                                  .toEither
                                  .left
                                  .map(_ => s"invalid folder timestamp '${value.createdAt}'")
                     _       <- Either.cond(!current.contains(id), (), s"duplicate folder ID ${value.id}")
                   yield current.updated(id, CatalogFolder(id, parent, name, created))
                 }
      files   <- disk.files.foldLeft[Either[String, Map[CatalogFileId, CatalogFile]]](Right(Map.empty)) { (result, value) =>
                   for
                     current   <- result
                     id        <- CatalogFileId.parse(value.id)
                     folder    <-
                       value.folder.map(CatalogFolderId.parse).fold[Either[String, Option[CatalogFolderId]]](Right(None))(_.map(Some(_)))
                     name      <- CatalogName.parse(value.name)
                     bits      <- KeyBits.fromString(value.blob)
                     blob      <- BinaryKey.blob(bits)
                     mediaType <- MediaTypeText.parse(value.mediaType)
                     created   <-
                       scala.util.Try(Instant.parse(value.createdAt)).toEither.left.map(_ => s"invalid file timestamp '${value.createdAt}'")
                     _         <- Either.cond(
                                    value.blockCount >= 0 && value.freshBlocks >= 0 && value.duplicateBlocks >= 0,
                                    (),
                                    "negative block counters",
                                  )
                     _         <- Either.cond(
                                    value.freshBlocks.toLong + value.duplicateBlocks.toLong == value.blockCount.toLong,
                                    (),
                                    "inconsistent block counters",
                                  )
                     _         <- Either.cond(!current.contains(id), (), s"duplicate file ID ${value.id}")
                   yield current.updated(
                     id,
                     CatalogFile(id, folder, name, blob, mediaType, value.blockCount, value.freshBlocks, value.duplicateBlocks, created),
                   )
                 }
      state    = State(folders, files)
      _       <- validateState(state)
    yield state

  private def validateState(state: State): Either[String, Unit] =
    val missingParent = state.folders.values.collectFirst {
      case folder if folder.parent.exists(id => !state.folders.contains(id)) => folder.id
    }
    val missingFolder = state.files.values.collectFirst { case file if file.folder.exists(id => !state.folders.contains(id)) => file.id }
    val entries       =
      state.folders.values.map(folder => folder.parent -> folder.name) ++ state.files.values.map(file => file.folder -> file.name)
    val duplicateName = entries
      .groupBy { case (parent, name) => parent -> name.value.toLowerCase(java.util.Locale.ROOT) }
      .collectFirst { case (_, values) if values.size > 1 => values.head._2.value }
    for
      _ <- Either.cond(missingParent.isEmpty, (), s"folder ${missingParent.get.value} has a missing parent")
      _ <- Either.cond(missingFolder.isEmpty, (), s"file ${missingFolder.get.value} has a missing folder")
      _ <- Either.cond(duplicateName.isEmpty, (), s"duplicate catalog name '${duplicateName.get}'")
      _ <- Either.cond(state.folders.keys.forall(id => !hasCycle(id, state.folders)), (), "folder hierarchy contains a cycle")
    yield ()

  private def hasCycle(id: CatalogFolderId, folders: Map[CatalogFolderId, CatalogFolder]): Boolean =
    @annotation.tailrec
    def loop(current: Option[CatalogFolderId], seen: Set[CatalogFolderId]): Boolean =
      current match
        case None                       => false
        case Some(value) if seen(value) => true
        case Some(value)                => loop(folders.get(value).flatMap(_.parent), seen + value)
    loop(Some(id), Set.empty)

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
