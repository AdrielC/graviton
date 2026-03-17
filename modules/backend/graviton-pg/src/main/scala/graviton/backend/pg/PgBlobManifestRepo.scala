package graviton.backend.pg

import graviton.core.bytes.{Digest, HashAlgo}
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.manifest.Manifest
import graviton.runtime.streaming.BlobStreamer
import graviton.runtime.stores.{BlobManifestRepo, StoredManifest}
import zio.*
import zio.stream.ZStream

import java.sql.{Connection, PreparedStatement, ResultSet}
import java.time.Instant
import javax.sql.DataSource

final class PgBlobManifestRepo(private val ds: DataSource) extends BlobManifestRepo:

  override def put(blob: BinaryKey.Blob, manifest: Manifest, ingestedAt: Instant): ZIO[Any, Throwable, Unit] =
    withTransaction { conn =>
      upsertBlob(conn, blob, manifest, ingestedAt) *>
        upsertBlocks(conn, manifest) *>
        insertBlobBlocks(conn, blob, manifest)
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
              // Check blob exists and get ingestedAt
              val blobPs        = conn.prepareStatement(
                """SELECT created_at FROM graviton.blob
                  |WHERE alg = ?::core.hash_alg AND hash_bytes = ? AND byte_length = ?""".stripMargin
              )
              val ingestedAtOpt =
                try
                  blobPs.setString(1, blobAlg)
                  blobPs.setBytes(2, blob.bits.digest.bytes)
                  blobPs.setLong(3, blob.bits.size)
                  val blobRs = blobPs.executeQuery()
                  if blobRs.next() then Some(Option(blobRs.getTimestamp(1)).map(_.toInstant).getOrElse(Instant.EPOCH))
                  else None
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

  private def openCursor(sql: String, blob: BinaryKey.Blob): Task[Cursor] =
    ZIO
      .fromEither(toDbAlg(blob.bits.algo))
      .mapError(msg => new IllegalArgumentException(msg))
      .flatMap { blobAlg =>
        ZIO.attemptBlocking {
          val conn = ds.getConnection()
          val ps   = conn.prepareStatement(sql)
          ps.setFetchSize(256)
          ps.setString(1, blobAlg)
          ps.setBytes(2, blob.bits.digest.bytes)
          ps.setLong(3, blob.bits.size)
          val rs   = ps.executeQuery()
          Cursor(conn, ps, rs)
        }
      }

  private def closeCursor(cursor: Cursor): UIO[Unit] =
    ZIO.attemptBlocking {
      try cursor.rs.close()
      finally
        try cursor.ps.close()
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

  private def upsertBlob(conn: Connection, blob: BinaryKey.Blob, manifest: Manifest, ingestedAt: Instant): Task[Unit] =
    val sql =
      """
        |INSERT INTO graviton.blob (alg, hash_bytes, byte_length, block_count, created_at, chunker, attrs)
        |VALUES (?::core.hash_alg, ?, ?, ?, ?, '{}'::jsonb, '{}'::jsonb)
        |ON CONFLICT (alg, hash_bytes, byte_length) DO NOTHING
        |""".stripMargin

    ZIO
      .fromEither(toDbAlg(blob.bits.algo))
      .mapError(msg => new IllegalArgumentException(msg))
      .flatMap { blobAlg =>
        ZIO.attemptBlocking {
          val ps = conn.prepareStatement(sql)
          try
            ps.setString(1, blobAlg)
            ps.setBytes(2, blob.bits.digest.bytes)
            ps.setLong(3, blob.bits.size)
            ps.setInt(4, manifest.entries.length)
            ps.setTimestamp(5, java.sql.Timestamp.from(ingestedAt))
            ps.executeUpdate()
            ()
          finally ps.close()
        }
      }

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
  val layer: ZLayer[DataSource, Nothing, BlobManifestRepo] =
    ZLayer.fromFunction(new PgBlobManifestRepo(_))

private final case class Cursor(conn: Connection, ps: PreparedStatement, rs: ResultSet)
