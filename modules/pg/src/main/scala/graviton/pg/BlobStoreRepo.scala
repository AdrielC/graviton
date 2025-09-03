package graviton.pg

import zio.*
import zio.Chunk
import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*

trait BlobStoreRepo:
  def upsert(row: BlobStoreRow): Task[Unit]
  def get(key: StoreKey): Task[Option[BlobStoreRow]]
  def listActive(): Task[Chunk[BlobStoreRow]]

final class BlobStoreRepoLive(xa: Transactor) extends BlobStoreRepo:

  def upsert(row: BlobStoreRow): Task[Unit] =
    transact(xa):
      ZIO.succeed {
        sql"""
          INSERT INTO blob_store (key, impl_id, build_fp, dv_schema_urn, dv_canonical_bin, dv_json_preview, status)
          VALUES (${row.key}, ${row.implId}, ${row.buildFp}, ${row.dvSchemaUrn}, ${row.dvCanonical}, ${row.dvJsonPreview}, ${row.status})
          ON CONFLICT (key) DO UPDATE
          SET updated_at = now(),
              version    = blob_store.version + 1
        """.update.run()
      }.unit

  def get(key: StoreKey): Task[Option[BlobStoreRow]] =
    connect(xa):
      ZIO.succeed {
        val rows = sql"""
          SELECT key, impl_id, build_fp, dv_schema_urn, dv_canonical_bin, dv_json_preview, status, version
          FROM blob_store WHERE key = $key
        """.query[BlobStoreRow].run()
        rows.headOption
      }

  def listActive(): Task[Chunk[BlobStoreRow]] =
    connect(xa):
      ZIO.succeed {
        val rows = sql"""
          SELECT key, impl_id, build_fp, dv_schema_urn, dv_canonical_bin, dv_json_preview, status, version
          FROM blob_store WHERE status = 'active'
        """.query[BlobStoreRow].run()
        Chunk.fromIterable(rows)
      }

object BlobStoreRepoLive:
  def layer: ZLayer[Transactor, Nothing, BlobStoreRepo] =
    ZLayer.fromFunction(new BlobStoreRepoLive(_))
