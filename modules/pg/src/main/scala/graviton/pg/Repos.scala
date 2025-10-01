package graviton.pg

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*
import graviton.db.{*, given}
import zio.*
import zio.Chunk
import zio.stream.*

final class StoreRepoLive(xa: TransactorZIO) extends StoreRepo:

  def upsert(row: StoreRow): Task[Int] =
    xa.transact {
      sql"""
        INSERT INTO store (key, impl_id, build_fp, dv_schema_urn, dv_canonical_bin, dv_json_preview, status)
        VALUES (${row.key}, ${row.implId}, ${row.buildFp}, ${row.dvSchemaUrn}, ${row.dvCanonical}, ${row.dvJsonPreview}, ${row.status})
        ON CONFLICT (key) DO UPDATE
        SET impl_id         = EXCLUDED.impl_id,
            build_fp       = EXCLUDED.build_fp,
            dv_schema_urn  = EXCLUDED.dv_schema_urn,
            dv_canonical_bin = EXCLUDED.dv_canonical_bin,
            dv_json_preview  = EXCLUDED.dv_json_preview,
            status          = EXCLUDED.status,
            updated_at      = now(),
            version         = store.version + 1
      """.update.run()
    }

  def get(key: StoreKey): Task[Option[StoreRow]] =
    xa.transact {
      sql"""
        SELECT key, impl_id, build_fp, dv_schema_urn, dv_canonical_bin, dv_json_preview, status, version
        FROM store WHERE key = $key
      """.query[StoreRow].run().headOption
    }

  def listActive(cursor: Option[Cursor] = None): Stream[Throwable, StoreRow] =
    ZStream.unwrapScoped {
      for
        cursorRef    <- Ref.make(cursor.getOrElse(Cursor.initial))
        initialState <- cursorRef.get
        pageSize      = cursor.map(_.pageSize).getOrElse(initialState.pageSize)
        stream        =
          ZStream.paginateChunkZIO(0L) { offset =>
            for {
              state         <- cursorRef.get
              existingTotal  = state.total.map(_.value)
              effectiveLimit =
                existingTotal
                  .map(total => math.min(pageSize, math.max(0L, total - offset)))
                  .getOrElse(pageSize)
              result        <-
                if effectiveLimit <= 0 then ZIO.succeed((Chunk.empty[StoreRow], Option.empty[Long]))
                else
                  xa
                    .transact {
                      sql"""
                        SELECT key,
                               impl_id,
                               build_fp,
                               dv_schema_urn,
                               dv_canonical_bin,
                               dv_json_preview,
                               status,
                               version,
                               count(*) OVER () AS total
                        FROM store
                        WHERE status = ${StoreStatus.Active}
                        ORDER BY updated_at DESC
                        LIMIT $effectiveLimit
                        OFFSET $offset
                      """
                        .query[(StoreRow, Long)]
                        .run()
                    }
                    .flatMap { rows =>
                      val chunk          = Chunk.fromIterable(rows.map(_._1))
                      val queriedTotal   = rows.headOption.map(_._2)
                      val combinedTotal  = queriedTotal.orElse(existingTotal)
                      val delta          = chunk.size.toLong
                      val updatedCursor  =
                        val base = combinedTotal
                          .map(total => state.withTotal(Max(total)))
                          .getOrElse(state)
                        base.next(delta)
                      val hasMoreByTotal = combinedTotal.exists(total => offset + delta < total)
                      val hasMoreByPage  = combinedTotal.isEmpty && delta == effectiveLimit && delta > 0
                      val nextOffset     = Option.when(chunk.nonEmpty && (hasMoreByTotal || hasMoreByPage))(offset + delta)
                      cursorRef.set(updatedCursor).as((chunk, nextOffset))
                    }
            } yield result
          }
      yield stream
    }

object StoreRepoLive:
  val layer: ZLayer[TransactorZIO, Nothing, StoreRepo] =
    ZLayer.fromFunction(new StoreRepoLive(_))

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

  def linkReplica(
    key: BlockKey,
    storeKey: StoreKey,
    sector: Option[String],
    size: PosLong,
    etag: Option[String],
    storageClass: Option[String],
  ): Task[Int] =
    xa.transact {
      sql"""
        INSERT INTO replica (algo_id, hash, store_key, sector, status, size_bytes, etag, storage_class)
        VALUES (${key.algoId}, ${key.hash}, $storeKey, $sector, ${ReplicaStatus.Active}, $size, $etag, $storageClass)
        ON CONFLICT (algo_id, hash, store_key) DO UPDATE
        SET status = EXCLUDED.status,
            size_bytes = EXCLUDED.size_bytes,
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
        chunk.mapZIO { case (BlockInsert(algoId, hash, len, offset), idx) =>
          val seq      = Math.toIntExact(idx)
          val blockKey = BlockKey(algoId, hash)
          xa.transact {
            sql"""
                INSERT INTO manifest_entry (blob_id, seq, block_algo_id, block_hash, offset_bytes, size_bytes)
                VALUES ($blobId, $seq, $algoId, $hash, $offset, $len)
              """.update.run()
          }.as(blockKey)
        }
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
