package graviton.pg

import zio.*
import zio.Chunk
import java.util.UUID
import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*

trait BlobStoreRepo:
  def upsert(row: BlobStoreRow): Task[Unit]
  def get(key: StoreKey): Task[Option[BlobStoreRow]]
  def listActive(): Task[Chunk[BlobStoreRow]]

final class BlobStoreRepoLive(xa: Transactor) extends BlobStoreRepo:
  def upsert(row: BlobStoreRow): Task[Unit] =
    transact(xa) {
      ZIO.attempt {
        sql"""
          INSERT INTO blob_store (key, impl_id, build_fp, dv_schema_urn, dv_canonical_bin, dv_json_preview, status)
          VALUES (${row.key}, ${row.implId}, ${row.buildFp}, ${row.dvSchemaUrn}, ${row.dvCanonical}, ${row.dvJsonPreview}, ${row.status})
          ON CONFLICT (key) DO UPDATE
          SET updated_at = now(),
              version    = blob_store.version + 1
        """.update.run(); ()
      }
    }

  def get(key: StoreKey): Task[Option[BlobStoreRow]] =
    transact(xa) {
      ZIO.attempt {
        sql"""
          SELECT key, impl_id, build_fp, dv_schema_urn, dv_canonical_bin, dv_json_preview, status, version
          FROM blob_store WHERE key = $key
        """.query[BlobStoreRow].run().headOption
      }
    }

  def listActive(): Task[Chunk[BlobStoreRow]] =
    transact(xa) {
      ZIO.attempt {
        val rows = sql"""
          SELECT key, impl_id, build_fp, dv_schema_urn, dv_canonical_bin, dv_json_preview, status, version
          FROM blob_store WHERE status = ${StoreStatus.Active}
        """.query[BlobStoreRow].run()
        Chunk.from(rows)
      }
    }

object BlobStoreRepoLive:
  val layer: ZLayer[Transactor, Nothing, BlobStoreRepo] =
    ZLayer.fromFunction(new BlobStoreRepoLive(_))

// ---- Block repository -------------------------------------------------------

trait BlockRepo:
  def upsertBlock(
      key: BlockKey,
      size: PosLong,
      inline: Option[SmallBytes]
  ): Task[Unit]
  def getHead(key: BlockKey): Task[Option[(PosLong, Boolean)]]
  def linkLocation(
      key: BlockKey,
      storeKey: StoreKey,
      uri: Option[String],
      len: PosLong,
      etag: Option[String],
      storageClass: Option[String]
  ): Task[Unit]

final class BlockRepoLive(xa: Transactor) extends BlockRepo:
  def upsertBlock(
      key: BlockKey,
      size: PosLong,
      inline: Option[SmallBytes]
  ): Task[Unit] =
    transact(xa) {
      ZIO.attempt {
        sql"""
          INSERT INTO block (algo_id, hash, size_bytes, inline_bytes)
          VALUES (${key.algoId}, ${key.hash}, $size, $inline)
          ON CONFLICT (algo_id, hash) DO NOTHING
        """.update.run(); ()
      }
    }

  def getHead(key: BlockKey): Task[Option[(PosLong, Boolean)]] =
    transact(xa) {
      ZIO.attempt {
        sql"""
          SELECT size_bytes, (inline_bytes IS NOT NULL) AS has_inline
          FROM block WHERE algo_id = ${key.algoId} AND hash = ${key.hash}
        """.query[(Long, Boolean)].run().headOption.map { case (s, b) =>
          (s.asInstanceOf[PosLong], b)
        }
      }
    }

  def linkLocation(
      key: BlockKey,
      storeKey: StoreKey,
      uri: Option[String],
      len: PosLong,
      etag: Option[String],
      storageClass: Option[String]
  ): Task[Unit] =
    transact(xa) {
      ZIO.attempt {
        sql"""
          INSERT INTO block_location (algo_id, hash, blob_store_key, uri, status, bytes_length, etag, storage_class)
          VALUES (${key.algoId}, ${key.hash}, $storeKey, $uri, ${LocationStatus.Active}, $len, $etag, $storageClass)
          ON CONFLICT (algo_id, hash, blob_store_key) DO UPDATE
          SET status = EXCLUDED.status,
              bytes_length = EXCLUDED.bytes_length,
              etag = EXCLUDED.etag,
              storage_class = EXCLUDED.storage_class,
              last_verified_at = now()
        """.update.run(); ()
      }
    }

object BlockRepoLive:
  val layer: ZLayer[Transactor, Nothing, BlockRepo] =
    ZLayer.fromFunction(new BlockRepoLive(_))

// ---- File repository --------------------------------------------------------

trait FileRepo:
  def upsertFile(key: FileKey): Task[UUID]
  def putFileBlocks(
      fileId: UUID,
      blocks: Chunk[(BlockKey, NonNegLong, PosLong)]
  ): Task[Unit]
  def findFileId(key: FileKey): Task[Option[UUID]]

final class FileRepoLive(xa: Transactor) extends FileRepo:
  def upsertFile(key: FileKey): Task[UUID] =
    transact(xa) {
      ZIO.attempt {
        val id = UUID.randomUUID()
        sql"""
          INSERT INTO file (id, algo_id, hash, size_bytes, media_type)
          VALUES ($id, ${key.algoId}, ${key.hash}, ${key.size}, ${key.mediaType})
          ON CONFLICT (algo_id, hash, size_bytes) DO UPDATE
          SET media_type = EXCLUDED.media_type
          RETURNING id
        """.query[UUID].run().head
      }
    }

  def putFileBlocks(
      fileId: UUID,
      blocks: Chunk[(BlockKey, NonNegLong, PosLong)]
  ): Task[Unit] =
    transact(xa) {
      ZIO.attempt {
        blocks.zipWithIndex.foreach { case ((bk, offset, len), seq) =>
          sql"""
            INSERT INTO file_block (file_id, seq, block_algo_id, block_hash, offset_bytes, length_bytes)
            VALUES ($fileId, $seq, ${bk.algoId}, ${bk.hash}, $offset, $len)
            ON CONFLICT (file_id, seq) DO UPDATE
            SET block_algo_id = EXCLUDED.block_algo_id,
                block_hash    = EXCLUDED.block_hash,
                offset_bytes  = EXCLUDED.offset_bytes,
                length_bytes  = EXCLUDED.length_bytes
          """.update.run()
        }
      }
    }

  def findFileId(key: FileKey): Task[Option[UUID]] =
    transact(xa) {
      ZIO.attempt {
        sql"""
          SELECT id FROM file
          WHERE algo_id = ${key.algoId} AND hash = ${key.hash} AND size_bytes = ${key.size}
        """.query[UUID].run().headOption
      }
    }

object FileRepoLive:
  val layer: ZLayer[Transactor, Nothing, FileRepo] =
    ZLayer.fromFunction(new FileRepoLive(_))
