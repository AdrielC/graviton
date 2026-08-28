package graviton.backend.pg

import graviton.core.bytes.{Digest, HashAlgo}
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.runtime.catalog.*
import graviton.shared.MediaTypeText
import zio.*
import zio.blocks.mediatype.MediaType

import java.sql.{Connection, ResultSet, SQLException}
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** PostgreSQL-backed catalog shared by every Graviton node. */
final class PgCatalog(private val dataSource: DataSource) extends Catalog:

  override def list(folderId: Option[CatalogFolderId]): IO[CatalogError, CatalogListing] =
    blocking("list") { connection =>
      val selected    = folderId.map(id => readFolder(connection, id).getOrElse(throw MissingFolder(id)))
      val breadcrumbs = selected.fold(Chunk.empty[CatalogFolder])(folder => readBreadcrumbs(connection, folder.id))
      val folders     = readFolders(connection, folderId)
      val files       = readFiles(connection, folderId)
      CatalogListing(selected, breadcrumbs, folders, files)
    }

  override def createFolder(
    parent: Option[CatalogFolderId],
    name: CatalogName,
  ): IO[CatalogError, CatalogFolder] =
    for
      id     <- CatalogFolderId.fresh
      folder <- blocking("create folder") { connection =>
                  requireFolder(connection, parent)
                  val statement = connection.prepareStatement(
                    """INSERT INTO graviton.catalog_entry (entry_id, parent_id, kind, name)
                      |VALUES (?, ?, 'folder', ?)
                      |RETURNING created_at""".stripMargin
                  )
                  try
                    statement.setObject(1, id.value)
                    parent.fold(statement.setNull(2, java.sql.Types.OTHER))(value => statement.setObject(2, value.value))
                    statement.setString(3, name.value)
                    val result = statement.executeQuery()
                    try
                      if !result.next() then throw new IllegalStateException("folder insert returned no row")
                      CatalogFolder(id, parent, name, timestamp(result, 1))
                    finally result.close()
                  finally statement.close()
                }.mapError(mapWriteError(name, _))
    yield folder

  override def getFile(id: CatalogFileId): IO[CatalogError, CatalogFile] =
    blocking("get file") { connection =>
      val statement = connection.prepareStatement(
        """SELECT entry_id, parent_id, name, blob_alg, blob_hash_bytes, blob_byte_length,
          |       media_type, block_count, fresh_blocks, duplicate_blocks, created_at
          |FROM graviton.catalog_entry WHERE entry_id = ? AND kind = 'file'""".stripMargin
      )
      try
        statement.setObject(1, id.value)
        val result = statement.executeQuery()
        try
          if result.next() then file(result)
          else throw MissingFile(id)
        finally result.close()
      finally statement.close()
    }

  override def attachFile(
    folder: Option[CatalogFolderId],
    name: CatalogName,
    blob: BinaryKey.Blob,
    mediaType: MediaType,
    stats: graviton.core.attributes.IngestStats,
  ): IO[CatalogError, CatalogFile] =
    for
      id   <- CatalogFileId.fresh
      file <- blocking("attach file") { connection =>
                requireFolder(connection, folder)
                val statement = connection.prepareStatement(
                  """INSERT INTO graviton.catalog_entry (
                    |  entry_id, parent_id, kind, name,
                    |  blob_alg, blob_hash_bytes, blob_byte_length, media_type,
                    |  block_count, fresh_blocks, duplicate_blocks
                    |)
                    |VALUES (?, ?, 'file', ?, ?::core.hash_alg, ?, ?, ?, ?, ?, ?)
                    |RETURNING created_at""".stripMargin
                )
                try
                  statement.setObject(1, id.value)
                  folder.fold(statement.setNull(2, java.sql.Types.OTHER))(value => statement.setObject(2, value.value))
                  statement.setString(3, name.value)
                  statement.setString(4, dbAlgorithm(blob.bits.algo))
                  statement.setBytes(5, blob.bits.digest.bytes)
                  statement.setLong(6, blob.bits.size)
                  statement.setString(7, MediaTypeText.render(mediaType))
                  statement.setInt(8, stats.blockCount)
                  statement.setInt(9, stats.freshBlocks)
                  statement.setInt(10, stats.duplicateBlocks)
                  val result = statement.executeQuery()
                  try
                    if !result.next() then throw new IllegalStateException("file insert returned no row")
                    CatalogFile(
                      id,
                      folder,
                      name,
                      blob,
                      mediaType,
                      stats.blockCount,
                      stats.freshBlocks,
                      stats.duplicateBlocks,
                      timestamp(result, 1),
                    )
                  finally result.close()
                finally statement.close()
              }.mapError(mapWriteError(name, _))
    yield file

  override def removeFile(id: CatalogFileId): IO[CatalogError, Unit] =
    delete("file", id.value).flatMap { count =>
      ZIO.fail(CatalogError.NotFound("file", id.value.toString)).unless(count == 1).unit
    }

  override def removeFolder(id: CatalogFolderId): IO[CatalogError, Unit] =
    delete("folder", id.value)
      .flatMap(count => ZIO.fail(CatalogError.NotFound("folder", id.value.toString)).unless(count == 1).unit)
      .catchSome {
        case CatalogError.Storage(_, sql: SQLException) if sql.getSQLState == "23503" =>
          ZIO.fail(CatalogError.FolderNotEmpty(id))
      }

  private def delete(kind: String, id: UUID): IO[CatalogError, Int] =
    blocking(s"remove $kind") { connection =>
      val statement = connection.prepareStatement("DELETE FROM graviton.catalog_entry WHERE entry_id = ? AND kind = ?")
      try
        statement.setObject(1, id)
        statement.setString(2, kind)
        statement.executeUpdate()
      finally statement.close()
    }

  private def readFolder(connection: Connection, id: CatalogFolderId): Option[CatalogFolder] =
    val statement = connection.prepareStatement(
      "SELECT entry_id, parent_id, name, created_at FROM graviton.catalog_entry WHERE entry_id = ? AND kind = 'folder'"
    )
    try
      statement.setObject(1, id.value)
      val result = statement.executeQuery()
      try Option.when(result.next())(folder(result))
      finally result.close()
    finally statement.close()

  private def readBreadcrumbs(connection: Connection, id: CatalogFolderId): Chunk[CatalogFolder] =
    val statement = connection.prepareStatement(
      """WITH RECURSIVE ancestors AS (
        |  SELECT entry_id, parent_id, name, created_at, 0 AS depth
        |  FROM graviton.catalog_entry WHERE entry_id = ? AND kind = 'folder'
        |  UNION ALL
        |  SELECT parent.entry_id, parent.parent_id, parent.name, parent.created_at, child.depth + 1
        |  FROM graviton.catalog_entry parent
        |  JOIN ancestors child ON parent.entry_id = child.parent_id
        |  WHERE parent.kind = 'folder'
        |)
        |SELECT entry_id, parent_id, name, created_at FROM ancestors ORDER BY depth DESC""".stripMargin
    )
    try
      statement.setObject(1, id.value)
      val result  = statement.executeQuery()
      val builder = ChunkBuilder.make[CatalogFolder]()
      try while result.next() do builder += folder(result)
      finally result.close()
      builder.result()
    finally statement.close()

  private def readFolders(connection: Connection, parent: Option[CatalogFolderId]): Chunk[CatalogFolder] =
    val (where, bind) = parentClause(parent)
    val statement     = connection.prepareStatement(
      s"SELECT entry_id, parent_id, name, created_at FROM graviton.catalog_entry WHERE kind = 'folder' AND $where ORDER BY name_key, entry_id"
    )
    try
      bind(statement)
      val result  = statement.executeQuery()
      val builder = ChunkBuilder.make[CatalogFolder]()
      try while result.next() do builder += folder(result)
      finally result.close()
      builder.result()
    finally statement.close()

  private def readFiles(connection: Connection, parent: Option[CatalogFolderId]): Chunk[CatalogFile] =
    val (where, bind) = parentClause(parent)
    val statement     = connection.prepareStatement(
      s"""SELECT entry_id, parent_id, name, blob_alg, blob_hash_bytes, blob_byte_length,
         |       media_type, block_count, fresh_blocks, duplicate_blocks, created_at
         |FROM graviton.catalog_entry WHERE kind = 'file' AND $where ORDER BY name_key, entry_id""".stripMargin
    )
    try
      bind(statement)
      val result  = statement.executeQuery()
      val builder = ChunkBuilder.make[CatalogFile]()
      try while result.next() do builder += file(result)
      finally result.close()
      builder.result()
    finally statement.close()

  private def parentClause(
    parent: Option[CatalogFolderId]
  ): (String, java.sql.PreparedStatement => Unit) =
    parent match
      case None        => "parent_id IS NULL" -> (_ => ())
      case Some(value) => "parent_id = ?"     -> (_.setObject(1, value.value))

  private def requireFolder(connection: Connection, folder: Option[CatalogFolderId]): Unit =
    folder.foreach(id => if readFolder(connection, id).isEmpty then throw MissingFolder(id))

  private def folder(result: ResultSet): CatalogFolder =
    CatalogFolder(
      CatalogFolderId
        .parse(result.getObject(1, classOf[UUID]).toString)
        .fold(message => throw new IllegalStateException(message), identity),
      Option(result.getObject(2, classOf[UUID])).map(value =>
        CatalogFolderId.parse(value.toString).fold(message => throw new IllegalStateException(message), identity)
      ),
      CatalogName.parse(result.getString(3)).fold(message => throw new IllegalStateException(message), identity),
      timestamp(result, 4),
    )

  private def file(result: ResultSet): CatalogFile =
    val algorithm = parseDbAlgorithm(result.getString(4))
    val digest    = Digest.fromBytes(result.getBytes(5)).fold(message => throw new IllegalStateException(message), identity)
    val bits      = KeyBits.create(algorithm, digest, result.getLong(6)).fold(message => throw new IllegalStateException(message), identity)
    val blob      = BinaryKey.blob(bits).fold(message => throw new IllegalStateException(message), identity)
    CatalogFile(
      CatalogFileId.parse(result.getObject(1, classOf[UUID]).toString).fold(message => throw new IllegalStateException(message), identity),
      Option(result.getObject(2, classOf[UUID])).map(value =>
        CatalogFolderId.parse(value.toString).fold(message => throw new IllegalStateException(message), identity)
      ),
      CatalogName.parse(result.getString(3)).fold(message => throw new IllegalStateException(message), identity),
      blob,
      MediaTypeText.parse(result.getString(7)).fold(message => throw new IllegalStateException(message), identity),
      result.getInt(8),
      result.getInt(9),
      result.getInt(10),
      timestamp(result, 11),
    )

  private def timestamp(result: ResultSet, index: Int): Instant =
    Option(result.getTimestamp(index)).map(_.toInstant).getOrElse(Instant.EPOCH)

  private def dbAlgorithm(algorithm: HashAlgo): String =
    algorithm match
      case HashAlgo.Sha256 => "sha256"
      case HashAlgo.Blake3 => "blake3"
      case other           => throw new IllegalArgumentException(s"Unsupported catalog hash algorithm: $other")

  private def parseDbAlgorithm(value: String): HashAlgo =
    value.trim.toLowerCase(java.util.Locale.ROOT) match
      case "sha256" => HashAlgo.Sha256
      case "blake3" => HashAlgo.Blake3
      case other    => throw new IllegalStateException(s"Unsupported catalog hash algorithm: $other")

  private def blocking[A](operation: String)(effect: Connection => A): IO[CatalogError, A] =
    ZIO
      .attemptBlocking {
        val connection = dataSource.getConnection()
        try effect(connection)
        finally connection.close()
      }
      .mapError {
        case MissingFolder(id) => CatalogError.NotFound("folder", id.value.toString)
        case MissingFile(id)   => CatalogError.NotFound("file", id.value.toString)
        case error             => CatalogError.Storage(operation, error)
      }

  private def mapWriteError(name: CatalogName, error: CatalogError): CatalogError =
    error match
      case CatalogError.Storage(_, sql: SQLException) if sql.getSQLState == "23505" => CatalogError.NameConflict(name)
      case other                                                                    => other

  private final case class MissingFolder(id: CatalogFolderId) extends RuntimeException
  private final case class MissingFile(id: CatalogFileId)     extends RuntimeException

object PgCatalog:
  val layer: ZLayer[DataSource, Nothing, Catalog] = ZLayer.fromFunction(new PgCatalog(_))
