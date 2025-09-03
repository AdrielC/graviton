package graviton.pg

import zio.*
import zio.Chunk
import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*

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
    transact(xa):
      ZIO.succeed {
        sql"""
          INSERT INTO block (algo_id, hash, size_bytes, inline_bytes)
          VALUES (${key.algoId}, ${key.hash}, ${size: Long}, $inline)
          ON CONFLICT (algo_id, hash) DO NOTHING
        """.update.run()
      }.unit

  def getHead(key: BlockKey): Task[Option[(PosLong, Boolean)]] =
    connect(xa):
      ZIO.succeed {
        val rows = sql"""
          SELECT size_bytes, (inline_bytes IS NOT NULL) AS has_inline
          FROM block WHERE algo_id = ${key.algoId} AND hash = ${key.hash}
        """.query[(Long, Boolean)].run()
        rows.headOption
      }

  def linkLocation(
      key: BlockKey,
      storeKey: StoreKey,
      uri: Option[String],
      len: PosLong,
      etag: Option[String],
      storageClass: Option[String]
  ): Task[Unit] =
    transact(xa):
      ZIO.succeed {
        sql"""
          INSERT INTO block_location (algo_id, hash, blob_store_key, uri, status, bytes_length, etag, storage_class)
          VALUES (${key.algoId}, ${key.hash}, $storeKey, $uri, 'active', ${len: Long}, $etag, $storageClass)
          ON CONFLICT (algo_id, hash, blob_store_key) DO UPDATE
          SET status = EXCLUDED.status,
              bytes_length = EXCLUDED.bytes_length,
              etag = EXCLUDED.etag,
              storage_class = EXCLUDED.storage_class,
              last_verified_at = now()
        """.update.run()
      }.unit

object BlockRepoLive:
  def layer: ZLayer[Transactor, Nothing, BlockRepo] =
    ZLayer.fromFunction(new BlockRepoLive(_))
