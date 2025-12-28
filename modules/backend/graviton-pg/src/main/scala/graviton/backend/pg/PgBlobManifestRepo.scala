package graviton.backend.pg

import graviton.core.bytes.{Digest, HashAlgo}
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.manifest.Manifest
import graviton.runtime.streaming.BlobStreamer
import graviton.runtime.stores.BlobManifestRepo
import zio.*
import zio.stream.ZStream

import java.sql.{Connection, PreparedStatement, ResultSet}
import javax.sql.DataSource

final class PgBlobManifestRepo(private val ds: DataSource) extends BlobManifestRepo:

  override def put(blob: BinaryKey.Blob, manifest: Manifest): ZIO[Any, Throwable, Unit] =
    withTransaction { conn =>
      upsertBlob(conn, blob, manifest) *>
        upsertBlocks(conn, manifest) *>
        insertBlobBlocks(conn, blob, manifest)
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

  private def upsertBlob(conn: Connection, blob: BinaryKey.Blob, manifest: Manifest): Task[Unit] =
    val sql =
      """
        |INSERT INTO graviton.blob (alg, hash_bytes, byte_length, block_count, chunker, attrs)
        |VALUES (?::core.hash_alg, ?, ?, ?, '{}'::jsonb, '{}'::jsonb)
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
