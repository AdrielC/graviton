package graviton.pg

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*
import graviton.db.{*, given}
import zio.*
import zio.Chunk
import zio.stream.*

final class BlobStoreRepoLive(xa: TransactorZIO) extends BlobStoreRepo:

  def upsert(row: BlobStoreRow): Task[Int] =
    xa.transact {
      sql"""
        INSERT INTO blob_store (key, impl_id, build_fp, dv_schema_urn, dv_canonical_bin, dv_json_preview, status)
        VALUES (${row.key}, ${row.implId}, ${row.buildFp}, ${row.dvSchemaUrn}, ${row.dvCanonical}, ${row.dvJsonPreview}, ${row.status})
        ON CONFLICT (key) DO UPDATE
        SET updated_at = now(),
            version    = blob_store.version + 1
      """.update.run()
    }

  def get(key: StoreKey): Task[Option[BlobStoreRow]] =
    xa.transact {
      sql"""
        SELECT key, impl_id, build_fp, dv_schema_urn, dv_canonical_bin, dv_json_preview, status, version
        FROM blob_store WHERE key = $key
      """.query[BlobStoreRow].run().headOption
    }

  def listActive(cursor: Option[Cursor] = None): Stream[Throwable, BlobStoreRow] =
    ZStream.unwrapScoped {
      for
        total       <- Ref.make(cursor.getOrElse(Cursor.initial))
        firstCursor <- total.get
        limit        =
          cursor
            .flatMap(_.total)
            .getOrElse(Max(Long.MaxValue))
            .value
            .min(cursor.map(_.offset).getOrElse(firstCursor.pageSize))
            .min(firstCursor.pageSize)
        rows         = {
          ZStream.paginateChunkZIO(0) { offset =>
            for {
              totalNow                    <- total.get
              (rows, newTotal, newOffset) <-
                xa.transact {
                  val rows = sql"""
                    SELECT count(*) as total, key, impl_id, build_fp, dv_schema_urn, dv_canonical_bin, dv_json_preview, status, version
                    FROM blob_store WHERE status = ${StoreStatus.Active}
                    ORDER BY updated_at DESC
                    Limit $limit
                    OFFSET $offset
                  """
                    .query[(Long, BlobStoreRow)]
                    .run()

                  val newTotal  = rows.headOption.map(_._1).orElse(totalNow.total).getOrElse(0L)
                  val newOffset = offset + rows.size
                  (Chunk.fromIterable(rows.view.map(_._2)), newTotal, newOffset)
                }

              current <- total.get
              _       <- total.set(current.withTotal(Max(newTotal)).next(newOffset))

            } yield (rows, Option(newOffset).filter(_ => newOffset < limit && current.total.getOrElse(Max(0L)) < Max(newTotal)))
          }
        }
      yield rows
    }

object BlobStoreRepoLive:
  val layer: ZLayer[TransactorZIO, Nothing, BlobStoreRepo] =
    ZLayer.fromFunction(new BlobStoreRepoLive(_))

// ---- Block repository -------------------------------------------------------

final class BlockRepoLive(xa: TransactorZIO) extends BlockRepo:

  def upsertBlock(
    key: BlockKey,
    size: PosLong,
    inline: Option[SmallBytes],
  ): Task[Int] =
    xa.transact {
      sql"""
        INSERT INTO block (algo_id, hash, size_bytes, inline_bytes)
        VALUES (${key.algoId}, ${key.hash}, $size, $inline)
        ON CONFLICT (algo_id, hash) DO NOTHING
      """.update.run()
    }

  def getHead(key: BlockKey): Task[Option[(PosLong, Boolean)]] =
    xa.transact {
      sql"""
        SELECT size_bytes, (inline_bytes IS NOT NULL) AS has_inline
        FROM block WHERE algo_id = ${key.algoId} AND hash = ${key.hash}
      """.query[(Long, Boolean)].run().headOption.map { case (s, b) =>
        (PosLong.applyUnsafe(s), b)
      }
    }

  def linkLocation(
    key: BlockKey,
    storeKey: StoreKey,
    uri: Option[String],
    len: PosLong,
    etag: Option[String],
    storageClass: Option[String],
  ): Task[Int] =
    xa.transact {
      sql"""
        INSERT INTO block_location (algo_id, hash, blob_store_key, uri, status, bytes_length, etag, storage_class)
        VALUES (${key.algoId}, ${key.hash}, $storeKey, $uri, ${LocationStatus.Active}, $len, $etag, $storageClass)
        ON CONFLICT (algo_id, hash, blob_store_key) DO UPDATE
        SET status = EXCLUDED.status,
            bytes_length = EXCLUDED.bytes_length,
            etag = EXCLUDED.etag,
            storage_class = EXCLUDED.storage_class,
            last_verified_at = now()
      """.update.run()
    }

object BlockRepoLive:
  val layer: ZLayer[TransactorZIO, Nothing, BlockRepo] =
    ZLayer.derive[BlockRepoLive]

// ---- File repository --------------------------------------------------------

final class BlobRepoLive(xa: TransactorZIO) extends BlobRepo:
  def upsertBlob(key: BlobKey): Task[java.util.UUID] =
    Random.nextUUID.flatMap(id =>
      xa.transact {
        sql"""
          INSERT INTO blob (id, algo_id, hash, size_bytes, media_type_hint)
          VALUES ($id, ${key.algoId}, ${key.hash}, ${key.size}, ${key.mediaTypeHint})
          ON CONFLICT (algo_id, hash, size_bytes) DO UPDATE
          SET media_type_hint = EXCLUDED.media_type_hint
          RETURNING id
        """.query[java.util.UUID].run().head
      }
    )

  def putBlobBlocks(
    blobId: java.util.UUID
  ): ZPipeline[Any, Throwable, BlockInsert, BlockKey] =
    ZPipeline.fromFunction((s: ZStream[Any, Throwable, BlockInsert]) =>
      s.zipWithIndex.mapChunksZIO { case chunk =>
        chunk
          .mapZIO { case (BlockInsert(algoId, hash, len, offset), idx) =>
            xa.transact {
              sql"""
                  INSERT INTO blob_block (blob_id, seq, block_algo_id, block_hash, offset_bytes, length_bytes)
                  VALUES ($blobId, $idx, $algoId, $hash, $offset, $len)
                """.returning[BlockKey].run()
            }.map(Chunk.fromIterable)
          }
          .map(_.flatten)
      }
    )

  def findBlobId(key: BlobKey): Task[Option[java.util.UUID]] =
    xa.transact {
      sql"""
        SELECT id FROM blob
        WHERE algo_id = ${key.algoId} AND hash = ${key.hash} AND size_bytes = ${key.size}
      """.query[java.util.UUID].run().headOption
    }

object BlobRepoLive:
  val layer: ZLayer[TransactorZIO, Nothing, BlobRepo] =
    ZLayer.derive[BlobRepoLive]
