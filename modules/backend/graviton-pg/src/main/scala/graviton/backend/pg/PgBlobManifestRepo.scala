package graviton.backend.pg

import graviton.core.bytes.{Digest, HashAlgo}
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.manifest.{Manifest, ManifestEntry}
import graviton.core.types.FileSize
import graviton.runtime.streaming.BlobStreamer
import graviton.runtime.stores.{BlobManifestRepo, StoredManifest, StoredManifestSummary}
import zio.*
import zio.stream.ZStream

import java.sql.{Connection, PreparedStatement, ResultSet}
import java.time.Instant
import javax.sql.DataSource

final class PgBlobManifestRepo(private val ds: DataSource) extends BlobManifestRepo:

  override def healthCheck: ZIO[Any, Throwable, Unit] =
    ZIO.attemptBlocking {
      val conn = ds.getConnection()
      try
        val statement = conn.prepareStatement("SELECT 1")
        try
          val result = statement.executeQuery()
          try
            if !result.next() || result.getInt(1) != 1 then throw new IllegalStateException("Postgres readiness query failed")
          finally result.close()
        finally statement.close()
      finally conn.close()
    }

  override def put(blob: BinaryKey.Blob, manifest: Manifest, ingestedAt: Instant): ZIO[Any, Throwable, Unit] =
    withTransaction { conn =>
      upsertBlob(conn, blob, manifest, ingestedAt) *>
        upsertBlocks(conn, manifest) *>
        insertBlobBlocks(conn, blob, manifest)
    }

  override def putStream(
    blob: BinaryKey.Blob,
    totalSize: FileSize,
    blockCount: Int,
    entries: ZStream[Any, Throwable, ManifestEntry],
    ingestedAt: Instant,
  ): ZIO[Any, Throwable, Unit] =
    ZIO
      .fromEither(BlobManifestRepo.validateStreamArguments(blob, totalSize, blockCount))
      .mapError(new IllegalArgumentException(_)) *>
      withTransaction { conn =>
        for
          _     <- upsertBlobSummary(conn, blob, blockCount, ingestedAt)
          _     <- deleteBlobBlocks(conn, blob)
          state <- writeEntryStream(conn, blob, entries)
          _     <- ZIO
                     .fail(
                       new IllegalArgumentException(
                         s"Manifest entry count mismatch: expected $blockCount, observed ${state.count}"
                       )
                     )
                     .unless(state.count == blockCount)
          _     <- ZIO
                     .fail(
                       new IllegalArgumentException(
                         s"Manifest size mismatch: expected ${totalSize.value}, observed ${state.offset}"
                       )
                     )
                     .unless(state.offset == totalSize.value)
        yield ()
      }

  override def get(blob: BinaryKey.Blob): ZIO[Any, Throwable, Option[StoredManifest]] =
    ZIO
      .fromEither(toDbAlg(blob.bits.algo))
      .mapError(msg => new IllegalArgumentException(msg))
      .flatMap { blobAlg =>
        ZIO
          .attemptBlocking {
            val conn = ds.getConnection()
            try
              // Check blob exists and reject materialized inspection of a large manifest.
              val blobPs        = conn.prepareStatement(
                """SELECT created_at, block_count FROM graviton.blob
                  |WHERE alg = ?::core.hash_alg AND hash_bytes = ? AND byte_length = ?""".stripMargin
              )
              val ingestedAtOpt =
                try
                  blobPs.setString(1, blobAlg)
                  blobPs.setBytes(2, blob.bits.digest.bytes)
                  blobPs.setLong(3, blob.bits.size)
                  val blobRs = blobPs.executeQuery()
                  try
                    if blobRs.next() then
                      val blockCount = blobRs.getInt(2)
                      if blockCount > BlobManifestRepo.MaxMaterializedEntries then
                        throw new IllegalArgumentException(
                          s"Manifest has $blockCount entries; materialized inspection is limited to ${BlobManifestRepo.MaxMaterializedEntries}. Use streamBlockRefs for reconstruction."
                        )
                      Some(Option(blobRs.getTimestamp(1)).map(_.toInstant).getOrElse(Instant.EPOCH))
                    else None
                  finally blobRs.close()
                finally blobPs.close()

              ingestedAtOpt match
                case None             => None
                case Some(ingestedAt) =>
                  // Read real spans from blob_block (block_offset + block_length)
                  val blockPs = conn.prepareStatement(
                    """SELECT
                      |  block_alg,
                      |  block_hash_bytes,
                      |  block_byte_length,
                      |  block_offset,
                      |  block_length
                      |FROM graviton.blob_block
                      |WHERE alg = ?::core.hash_alg
                      |  AND hash_bytes = ?
                      |  AND byte_length = ?
                      |ORDER BY ordinal ASC""".stripMargin
                  )
                  try
                    blockPs.setString(1, blobAlg)
                    blockPs.setBytes(2, blob.bits.digest.bytes)
                    blockPs.setLong(3, blob.bits.size)
                    val blockRs = blockPs.executeQuery()
                    try
                      import graviton.core.manifest.ManifestEntry
                      import graviton.core.ranges.Span
                      import graviton.core.types.BlobOffset

                      val entries = scala.collection.mutable.ListBuffer.empty[ManifestEntry]
                      while blockRs.next() do
                        val blockAlgStr = blockRs.getString(1)
                        val blockHash   = blockRs.getBytes(2)
                        val blockLen    = blockRs.getLong(3)
                        val offset      = blockRs.getLong(4)
                        val length      = blockRs.getLong(5)

                        val blockAlg = parseDbAlg(blockAlgStr).getOrElse(
                          throw new IllegalArgumentException(s"Unsupported hash algorithm '$blockAlgStr'")
                        )
                        val digest   = Digest
                          .fromBytes(blockHash)
                          .fold(
                            msg => throw new IllegalArgumentException(msg),
                            identity,
                          )
                        val bits     = KeyBits
                          .create(blockAlg, digest, blockLen)
                          .fold(
                            msg => throw new IllegalArgumentException(msg),
                            identity,
                          )
                        val key      = BinaryKey
                          .block(bits)
                          .fold(
                            msg => throw new IllegalArgumentException(msg),
                            identity,
                          )

                        val start = BlobOffset.unsafe(offset)
                        val end   = BlobOffset.unsafe(offset + length - 1L)
                        val span  = Span.unsafe(start, end)
                        entries += ManifestEntry(key, span, Map.empty)

                      Some((ingestedAt, entries.toList))
                    finally blockRs.close()
                  finally blockPs.close()
            finally conn.close()
          }
          .flatMap {
            case None                                  => ZIO.succeed(None)
            case Some((_, entries)) if entries.isEmpty => ZIO.succeed(None)
            case Some((ingestedAt, entries))           =>
              ZIO
                .fromEither(Manifest.fromEntries(entries))
                .mapBoth(
                  msg => new IllegalArgumentException(msg),
                  m => Some(StoredManifest(m, ingestedAt)),
                )
          }
      }

  override def getSummary(blob: BinaryKey.Blob): ZIO[Any, Throwable, Option[StoredManifestSummary]] =
    ZIO
      .fromEither(toDbAlg(blob.bits.algo))
      .mapError(message => new IllegalArgumentException(message))
      .flatMap { blobAlg =>
        ZIO.attemptBlocking {
          val conn = ds.getConnection()
          try
            val ps = conn.prepareStatement(
              """SELECT byte_length, block_count, created_at
                |FROM graviton.blob
                |WHERE alg = ?::core.hash_alg AND hash_bytes = ? AND byte_length = ?""".stripMargin
            )
            try
              ps.setString(1, blobAlg)
              ps.setBytes(2, blob.bits.digest.bytes)
              ps.setLong(3, blob.bits.size)
              val rs = ps.executeQuery()
              try
                if !rs.next() then None
                else
                  val size = FileSize
                    .either(rs.getLong(1))
                    .fold(message => throw new IllegalArgumentException(message), identity)
                  Some(
                    StoredManifestSummary(
                      totalSize = size,
                      blockCount = rs.getInt(2),
                      ingestedAt = Option(rs.getTimestamp(3)).map(_.toInstant).getOrElse(Instant.EPOCH),
                    )
                  )
              finally rs.close()
            finally ps.close()
          finally conn.close()
        }
      }

  override def list: ZIO[Any, Throwable, Chunk[(BinaryKey.Blob, StoredManifest)]] =
    val keys = ZIO.attemptBlocking {
      val conn = ds.getConnection()
      try
        val ps = conn.prepareStatement(
          """SELECT alg, hash_bytes, byte_length
            |FROM graviton.blob
            |ORDER BY created_at DESC, byte_length DESC""".stripMargin
        )
        try
          val rs     = ps.executeQuery()
          val result = ChunkBuilder.make[BinaryKey.Blob]()
          while rs.next() do
            val algorithmText = rs.getString(1)
            val digestBytes   = rs.getBytes(2)
            val byteLength    = rs.getLong(3)
            val algorithm     = parseDbAlg(algorithmText).getOrElse(
              throw new IllegalArgumentException(s"Unsupported hash algorithm '$algorithmText'")
            )
            val digest        = Digest
              .fromBytes(digestBytes)
              .fold(message => throw new IllegalArgumentException(message), identity)
            val bits          = KeyBits
              .create(algorithm, digest, byteLength)
              .fold(message => throw new IllegalArgumentException(message), identity)
            val blob          = BinaryKey
              .blob(bits)
              .fold(message => throw new IllegalArgumentException(message), identity)
            result += blob
          result.result()
        finally ps.close()
      finally conn.close()
    }

    keys.flatMap { blobs =>
      ZIO
        .foreach(blobs) { blob =>
          get(blob).map(_.map(stored => blob -> stored))
        }
        .map(entries => Chunk.fromIterable(entries.flatten))
    }

  override def listSummaries: ZIO[Any, Throwable, Chunk[(BinaryKey.Blob, StoredManifestSummary)]] =
    ZIO.attemptBlocking {
      val conn = ds.getConnection()
      try
        val ps = conn.prepareStatement(
          """SELECT alg, hash_bytes, byte_length, block_count, created_at
            |FROM graviton.blob
            |ORDER BY created_at DESC, byte_length DESC""".stripMargin
        )
        try
          val rs     = ps.executeQuery()
          val result = ChunkBuilder.make[(BinaryKey.Blob, StoredManifestSummary)]()
          try
            while rs.next() do
              val algorithm = parseDbAlg(rs.getString(1)).getOrElse(
                throw new IllegalArgumentException(s"Unsupported hash algorithm '${rs.getString(1)}'")
              )
              val digest    = Digest
                .fromBytes(rs.getBytes(2))
                .fold(message => throw new IllegalArgumentException(message), identity)
              val size      = FileSize
                .either(rs.getLong(3))
                .fold(message => throw new IllegalArgumentException(message), identity)
              val bits      = KeyBits
                .create(algorithm, digest, size.value)
                .fold(message => throw new IllegalArgumentException(message), identity)
              val blob      = BinaryKey
                .blob(bits)
                .fold(message => throw new IllegalArgumentException(message), identity)
              result += blob -> StoredManifestSummary(
                size,
                rs.getInt(4),
                Option(rs.getTimestamp(5)).map(_.toInstant).getOrElse(Instant.EPOCH),
              )
            result.result()
          finally rs.close()
        finally ps.close()
      finally conn.close()
    }

  override def delete(blob: BinaryKey.Blob): ZIO[Any, Throwable, Boolean] =
    ZIO
      .fromEither(toDbAlg(blob.bits.algo))
      .mapError(message => new IllegalArgumentException(message))
      .flatMap { blobAlg =>
        withTransaction { conn =>
          deleteRows(conn, "graviton.blob_manifest_page", blobAlg, blob) *>
            deleteRows(conn, "graviton.blob_block", blobAlg, blob) *>
            deleteRows(conn, "graviton.blob", blobAlg, blob).map(_ > 0)
        }
      }

  override def streamBlockRefs(blob: BinaryKey.Blob): ZStream[Any, Throwable, BlobStreamer.BlockRef] =
    val sql =
      """
        |SELECT
        |  ordinal,
        |  block_alg,
        |  block_hash_bytes,
        |  block_byte_length
        |FROM graviton.blob_block
        |WHERE alg = ?::core.hash_alg
        |  AND hash_bytes = ?
        |  AND byte_length = ?
        |ORDER BY ordinal ASC
        |""".stripMargin

    ZStream.acquireReleaseWith(openCursor(sql, blob))(closeCursor).flatMap { cursor =>
      ZStream.unfoldZIO(cursor) { c =>
        ZIO.attemptBlocking(c.rs.next()).flatMap { hasNext =>
          if !hasNext then ZIO.succeed(None)
          else readBlockRef(c.rs).map(ref => Some((ref, c)))
        }
      }
    }

  private def withTransaction[A](f: Connection => Task[A]): Task[A] =
    ZIO.scoped {
      ZIO
        .acquireRelease(ZIO.attemptBlocking(ds.getConnection()))(c => ZIO.attemptBlocking(c.close()).orDie)
        .flatMap { conn =>
          ZIO.attemptBlocking(conn.setAutoCommit(false)) *>
            f(conn).tapBoth(
              _ => ZIO.attemptBlocking(conn.rollback()).ignore,
              _ => ZIO.attemptBlocking(conn.commit()).unit,
            )
        }
    }

  private def deleteRows(
    conn: Connection,
    table: String,
    blobAlg: String,
    blob: BinaryKey.Blob,
  ): Task[Int] =
    ZIO.attemptBlocking {
      val statement = conn.prepareStatement(
        s"""DELETE FROM $table
           |WHERE alg = ?::core.hash_alg
           |  AND hash_bytes = ?
           |  AND byte_length = ?""".stripMargin
      )
      try
        statement.setString(1, blobAlg)
        statement.setBytes(2, blob.bits.digest.bytes)
        statement.setLong(3, blob.bits.size)
        statement.executeUpdate()
      finally statement.close()
    }

  private def openCursor(sql: String, blob: BinaryKey.Blob): Task[Cursor] =
    ZIO
      .fromEither(toDbAlg(blob.bits.algo))
      .mapError(msg => new IllegalArgumentException(msg))
      .flatMap { blobAlg =>
        ZIO.attemptBlocking {
          val conn = ds.getConnection()
          try
            conn.setReadOnly(true)
            conn.setAutoCommit(false)
            val ps = conn.prepareStatement(sql)
            try
              ps.setFetchSize(256)
              ps.setString(1, blobAlg)
              ps.setBytes(2, blob.bits.digest.bytes)
              ps.setLong(3, blob.bits.size)
              val rs = ps.executeQuery()
              Cursor(conn, ps, rs)
            catch
              case error: Throwable =>
                ps.close()
                throw error
          catch
            case error: Throwable =>
              conn.close()
              throw error
        }
      }

  private def closeCursor(cursor: Cursor): UIO[Unit] =
    ZIO.attemptBlocking {
      try cursor.rs.close()
      finally
        try cursor.ps.close()
        finally
          try cursor.conn.rollback()
          finally cursor.conn.close()
    }.orDie

  private def readBlockRef(rs: ResultSet): Task[BlobStreamer.BlockRef] =
    for
      ordinal     <- ZIO.attempt(rs.getInt(1).toLong)
      blockAlgStr <- ZIO.attempt(rs.getString(2))
      blockHash   <- ZIO.attempt(rs.getBytes(3))
      blockLen    <- ZIO.attempt(rs.getLong(4))
      blockAlg    <- ZIO
                       .fromOption(parseDbAlg(blockAlgStr))
                       .mapError(_ => new IllegalArgumentException(s"Unsupported hash algorithm '$blockAlgStr'"))
      digest      <- ZIO.fromEither(Digest.fromBytes(blockHash)).mapError(msg => new IllegalArgumentException(msg))
      bits        <- ZIO.fromEither(KeyBits.create(blockAlg, digest, blockLen)).mapError(msg => new IllegalArgumentException(msg))
      key         <- ZIO.fromEither(BinaryKey.block(bits)).mapError(msg => new IllegalArgumentException(msg))
    yield BlobStreamer.BlockRef(ordinal, key)

  private def upsertBlobSummary(
    conn: Connection,
    blob: BinaryKey.Blob,
    blockCount: Int,
    ingestedAt: Instant,
  ): Task[Unit] =
    val sql =
      """
        |INSERT INTO graviton.blob (alg, hash_bytes, byte_length, block_count, created_at, chunker, attrs)
        |VALUES (?::core.hash_alg, ?, ?, ?, ?, '{}'::jsonb, '{}'::jsonb)
        |ON CONFLICT (alg, hash_bytes, byte_length) DO UPDATE SET
        |  block_count = EXCLUDED.block_count,
        |  created_at = EXCLUDED.created_at
        |""".stripMargin

    for
      _       <- ZIO
                   .fail(new IllegalArgumentException(s"Manifest block count must be within 1..${BlobManifestRepo.MaxEntries}"))
                   .unless(blockCount >= 1 && blockCount <= BlobManifestRepo.MaxEntries)
      blobAlg <- ZIO.fromEither(toDbAlg(blob.bits.algo)).mapError(message => new IllegalArgumentException(message))
      _       <- ZIO.attemptBlocking {
                   val ps = conn.prepareStatement(sql)
                   try
                     ps.setString(1, blobAlg)
                     ps.setBytes(2, blob.bits.digest.bytes)
                     ps.setLong(3, blob.bits.size)
                     ps.setInt(4, blockCount)
                     ps.setTimestamp(5, java.sql.Timestamp.from(ingestedAt))
                     ps.executeUpdate()
                     ()
                   finally ps.close()
                 }
    yield ()

  private def deleteBlobBlocks(conn: Connection, blob: BinaryKey.Blob): Task[Unit] =
    ZIO
      .fromEither(toDbAlg(blob.bits.algo))
      .mapError(message => new IllegalArgumentException(message))
      .flatMap(blobAlg => deleteRows(conn, "graviton.blob_block", blobAlg, blob).unit)

  private def writeEntryStream(
    conn: Connection,
    blob: BinaryKey.Blob,
    entries: ZStream[Any, Throwable, ManifestEntry],
  ): Task[PgBlobManifestRepo.StreamState] =
    val blockSql =
      """
        |INSERT INTO graviton.block (alg, hash_bytes, byte_length, attrs)
        |VALUES (?::core.hash_alg, ?, ?, '{}'::jsonb)
        |ON CONFLICT (alg, hash_bytes, byte_length) DO NOTHING
        |""".stripMargin
    val entrySql =
      """
        |INSERT INTO graviton.blob_block (
        |  alg, hash_bytes, byte_length,
        |  ordinal,
        |  block_alg, block_hash_bytes, block_byte_length,
        |  block_offset, block_length
        |)
        |VALUES (?::core.hash_alg, ?, ?, ?, ?::core.hash_alg, ?, ?, ?, ?)
        |""".stripMargin

    ZIO.scoped {
      for
        blobAlg    <- ZIO.fromEither(toDbAlg(blob.bits.algo)).mapError(message => new IllegalArgumentException(message))
        statements <- ZIO.acquireRelease(
                        ZIO.attemptBlocking(
                          PgBlobManifestRepo.Statements(
                            conn.prepareStatement(blockSql),
                            conn.prepareStatement(entrySql),
                          )
                        )
                      )(current => ZIO.attemptBlocking(current.close()).orDie)
        state      <- entries
                        .rechunk(PgBlobManifestRepo.WriteBatchEntries)
                        .chunks
                        .runFoldZIO(PgBlobManifestRepo.StreamState.empty) { (current, batch) =>
                          ZIO.attemptBlocking {
                            var state = current
                            batch.foreach { entry =>
                              if state.count >= BlobManifestRepo.MaxEntries then
                                throw new IllegalArgumentException(s"Manifest exceeds ${BlobManifestRepo.MaxEntries} entries")
                              if entry.annotations.nonEmpty then
                                throw new IllegalArgumentException("CAS manifest entries must not carry non-semantic annotations")

                              val block  = entry.key match
                                case value: BinaryKey.Block => value
                                case other                  =>
                                  throw new IllegalArgumentException(s"Manifest entry key must be a block key, got $other")
                              val start  = entry.span.startInclusive.value
                              val length = entry.span.endInclusive.value - start + 1L
                              if start != state.offset then
                                throw new IllegalArgumentException(
                                  s"Manifest entry ${state.count} starts at $start, expected ${state.offset}"
                                )
                              if length <= 0L || length != block.bits.size then
                                throw new IllegalArgumentException(
                                  s"Manifest entry ${state.count} length $length does not match block size ${block.bits.size}"
                                )

                              val blockAlg = toDbAlg(block.bits.algo)
                                .fold(message => throw new IllegalArgumentException(message), identity)
                              statements.blocks.setString(1, blockAlg)
                              statements.blocks.setBytes(2, block.bits.digest.bytes)
                              statements.blocks.setLong(3, block.bits.size)
                              statements.blocks.addBatch()

                              statements.entries.setString(1, blobAlg)
                              statements.entries.setBytes(2, blob.bits.digest.bytes)
                              statements.entries.setLong(3, blob.bits.size)
                              statements.entries.setInt(4, state.count)
                              statements.entries.setString(5, blockAlg)
                              statements.entries.setBytes(6, block.bits.digest.bytes)
                              statements.entries.setLong(7, block.bits.size)
                              statements.entries.setLong(8, start)
                              statements.entries.setLong(9, length)
                              statements.entries.addBatch()

                              state = PgBlobManifestRepo.StreamState(
                                state.count + 1,
                                java.lang.Math.addExact(state.offset, length),
                              )
                            }
                            statements.blocks.executeBatch()
                            statements.entries.executeBatch()
                            state
                          }
                        }
      yield state
    }

  private def upsertBlob(conn: Connection, blob: BinaryKey.Blob, manifest: Manifest, ingestedAt: Instant): Task[Unit] =
    ZIO
      .fail(
        new IllegalArgumentException(
          s"Manifest size ${manifest.size} does not match blob key size ${blob.bits.size}"
        )
      )
      .unless(manifest.size == blob.bits.size) *>
      upsertBlobSummary(conn, blob, manifest.entries.length, ingestedAt)

  private def upsertBlocks(conn: Connection, manifest: Manifest): Task[Unit] =
    val sql =
      """
        |INSERT INTO graviton.block (alg, hash_bytes, byte_length, attrs)
        |VALUES (?::core.hash_alg, ?, ?, '{}'::jsonb)
        |ON CONFLICT (alg, hash_bytes, byte_length) DO NOTHING
        |""".stripMargin

    for
      rows <- ZIO.foreach(manifest.entries) { e =>
                e.key match
                  case block: BinaryKey.Block =>
                    ZIO
                      .fromEither(toDbAlg(block.bits.algo))
                      .mapError(msg => new IllegalArgumentException(msg))
                      .map(alg => (alg, block.bits.digest.bytes, block.bits.size))
                  case other                  =>
                    ZIO.fail(new IllegalArgumentException(s"Manifest entry key must be a block key, got $other"))
              }
      _    <- ZIO.attemptBlocking {
                val ps = conn.prepareStatement(sql)
                try
                  rows.foreach { case (alg, hashBytes, byteLength) =>
                    ps.setString(1, alg)
                    ps.setBytes(2, hashBytes)
                    ps.setLong(3, byteLength)
                    ps.addBatch()
                  }
                  ps.executeBatch()
                  ()
                finally ps.close()
              }
    yield ()

  private def insertBlobBlocks(conn: Connection, blob: BinaryKey.Blob, manifest: Manifest): Task[Unit] =
    val deleteSql =
      """
        |DELETE FROM graviton.blob_block
        |WHERE alg = ?::core.hash_alg
        |  AND hash_bytes = ?
        |  AND byte_length = ?
        |""".stripMargin

    val deleteOld: Task[Unit] =
      ZIO
        .fromEither(toDbAlg(blob.bits.algo))
        .mapError(msg => new IllegalArgumentException(msg))
        .flatMap { blobAlg =>
          ZIO.attemptBlocking {
            val deletePs = conn.prepareStatement(deleteSql)
            try
              deletePs.setString(1, blobAlg)
              deletePs.setBytes(2, blob.bits.digest.bytes)
              deletePs.setLong(3, blob.bits.size)
              deletePs.executeUpdate()
              ()
            finally deletePs.close()
          }
        }

    val insertSql =
      """
        |INSERT INTO graviton.blob_block (
        |  alg, hash_bytes, byte_length,
        |  ordinal,
        |  block_alg, block_hash_bytes, block_byte_length,
        |  block_offset, block_length
        |)
        |VALUES (?::core.hash_alg, ?, ?, ?, ?::core.hash_alg, ?, ?, ?, ?)
        |""".stripMargin

    for
      blobAlg <- ZIO.fromEither(toDbAlg(blob.bits.algo)).mapError(msg => new IllegalArgumentException(msg))
      rows    <- ZIO.foreach(manifest.entries.zipWithIndex) { case (e, idx) =>
                   e.key match
                     case block: BinaryKey.Block =>
                       val span = e.span
                       val off  = span.startInclusive
                       val len  = span.endInclusive.value - span.startInclusive.value + 1L
                       ZIO
                         .fromEither(toDbAlg(block.bits.algo))
                         .mapError(msg => new IllegalArgumentException(msg))
                         .map(blockAlg => (idx, blockAlg, block.bits.digest.bytes, block.bits.size, off, len))
                     case other                  =>
                       ZIO.fail(new IllegalArgumentException(s"Manifest entry key must be a block key, got $other"))
                 }
      _       <- deleteOld
      _       <- ZIO.attemptBlocking {
                   val ps = conn.prepareStatement(insertSql)
                   try
                     rows.foreach { case (idx, blockAlg, blockHashBytes, blockByteLength, off, len) =>
                       ps.setString(1, blobAlg)
                       ps.setBytes(2, blob.bits.digest.bytes)
                       ps.setLong(3, blob.bits.size)
                       ps.setInt(4, idx)
                       ps.setString(5, blockAlg)
                       ps.setBytes(6, blockHashBytes)
                       ps.setLong(7, blockByteLength)
                       ps.setLong(8, off.value)
                       ps.setLong(9, len)
                       ps.addBatch()
                     }
                     ps.executeBatch()
                     ()
                   finally ps.close()
                 }
    yield ()

  private def toDbAlg(algo: HashAlgo): Either[String, String] =
    algo match
      case HashAlgo.Sha256 => Right("sha256")
      case HashAlgo.Blake3 => Right("blake3")
      case other           => Left(s"Unsupported hash algorithm for v1 schema: $other")

  private def parseDbAlg(value: String): Option[HashAlgo] =
    value.trim.toLowerCase match
      case "sha256" => Some(HashAlgo.Sha256)
      case "blake3" => Some(HashAlgo.Blake3)
      case _        => None

object PgBlobManifestRepo:
  private val WriteBatchEntries = 512

  private final case class StreamState(count: Int, offset: Long)
  private object StreamState:
    val empty: StreamState = StreamState(0, 0L)

  private final case class Statements(
    blocks: PreparedStatement,
    entries: PreparedStatement,
  ):
    def close(): Unit =
      try blocks.close()
      finally entries.close()

  val layer: ZLayer[DataSource, Nothing, BlobManifestRepo] =
    ZLayer.fromFunction(new PgBlobManifestRepo(_))

private final case class Cursor(conn: Connection, ps: PreparedStatement, rs: ResultSet)
