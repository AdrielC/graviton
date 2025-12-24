package quasar.legacy.db

import zio.*

import java.sql.{Connection, PreparedStatement, ResultSet}
import java.util.UUID
import javax.sql.DataSource

final class JdbcLegacyMappings(dataSource: DataSource) extends LegacyMappings:

  override def lookupDoc(orgId: UUID, ref: LegacyDocRef): UIO[Option[(UUID, LegacyImportStatus)]] =
    ZIO
      .attemptBlocking {
        withConnection { conn =>
          val ps =
            conn.prepareStatement(
              """
              SELECT doc_id, status
              FROM quasar.legacy_doc_map
              WHERE org_id = ? AND legacy_repo = ? AND legacy_doc_id = ?
              """.stripMargin
            )
          ps.setObject(1, orgId)
          ps.setString(2, ref.repo)
          ps.setString(3, ref.docId)
          val rs = ps.executeQuery()
          if rs.next() then
            val docId  = rs.getObject(1).asInstanceOf[UUID]
            val status = LegacyImportStatus.valueOf(rs.getString(2))
            Some(docId -> status)
          else None
        }
      }
      .orElseSucceed(None)

  override def upsertDoc(
    orgId: UUID,
    ref: LegacyDocRef,
    documentId: UUID,
    status: LegacyImportStatus,
  ): IO[Throwable, Unit] =
    ZIO.attemptBlocking {
      withConnection { conn =>
        val ps =
          conn.prepareStatement(
            """
            INSERT INTO quasar.legacy_doc_map (org_id, legacy_repo, legacy_doc_id, doc_id, status)
            VALUES (?, ?, ?, ?, ?::quasar.legacy_import_status)
            ON CONFLICT (org_id, legacy_repo, legacy_doc_id) DO UPDATE
            SET doc_id = EXCLUDED.doc_id,
                status = EXCLUDED.status,
                imported_at = core.now_utc()
            """.stripMargin
          )
        ps.setObject(1, orgId)
        ps.setString(2, ref.repo)
        ps.setString(3, ref.docId)
        ps.setObject(4, documentId)
        ps.setString(5, status.toString)
        val _  = ps.executeUpdate()
        ()
      }
    }

  override def lookupBinary(orgId: UUID, ref: LegacyBinaryRef): UIO[Option[BlobKey]] =
    ZIO
      .attemptBlocking {
        withConnection { conn =>
          val ps =
            conn.prepareStatement(
              """
              SELECT blob_key
              FROM quasar.legacy_binary_map
              WHERE org_id = ? AND legacy_repo = ? AND legacy_binary_hash = ?
              """.stripMargin
            )
          ps.setObject(1, orgId)
          ps.setString(2, ref.repo)
          ps.setString(3, ref.binaryHash)
          val rs = ps.executeQuery()
          if rs.next() then Some(BlobKey(rs.getString(1))) else None
        }
      }
      .orElseSucceed(None)

  override def upsertBinary(orgId: UUID, ref: LegacyBinaryRef, blob: BlobKey): IO[Throwable, Unit] =
    ZIO.attemptBlocking {
      withConnection { conn =>
        val ps =
          conn.prepareStatement(
            """
            INSERT INTO quasar.legacy_binary_map (org_id, legacy_repo, legacy_binary_hash, blob_key)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (org_id, legacy_repo, legacy_binary_hash) DO UPDATE
            SET blob_key = EXCLUDED.blob_key,
                imported_at = core.now_utc()
            """.stripMargin
          )
        ps.setObject(1, orgId)
        ps.setString(2, ref.repo)
        ps.setString(3, ref.binaryHash)
        ps.setString(4, blob.value)
        val _  = ps.executeUpdate()
        ()
      }
    }

  private def withConnection[A](f: Connection => A): A =
    val conn = dataSource.getConnection()
    try f(conn)
    finally conn.close()

object JdbcLegacyMappings:
  val layer: ZLayer[DataSource, Nothing, LegacyMappings] =
    ZLayer.fromFunction(ds => new JdbcLegacyMappings(ds))
