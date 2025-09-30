package graviton.pg

import zio.*
import zio.stream.*
import zio.Chunk
import java.util.UUID
import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*
import io.github.iltotore.iron.{zio as _, *}
import io.github.iltotore.iron.constraint.all.*
import zio.prelude.*
import zio.prelude.classic.Monoid

opaque type Max[A] <: A = A
object Max:
  def apply[A](a: A): Max[A]              = a
  def unapply(a: Max[Long]): Option[Long] = Some(a)
  given [A](using PartialOrd[A], Identity[A]): Monoid[Max[A]] with
    def identity: Max[A]                            = Max(Identity[A].identity)
    def combine(a: => Max[A], b: => Max[A]): Max[A] = a.maximum(b)

  extension [A](a: Max[A])
    def value: A                                            = a
    def maximum(other: Max[A])(using PartialOrd[A]): Max[A] =
      if a > other then a else other

type BlockInsert = (key: BlockKey, offset: NonNegLong, length: PosLong)

case class Cursor(queryId: Option[UUID], offset: Long, total: Option[Max[Long]], pageSize: Long):
  def isLast: Boolean                  = total.exists(_ <= offset) || pageSize == 0
  def next(lastPageSize: Long): Cursor = Cursor(
    queryId,
    offset + lastPageSize,
    total.filter(_ > offset + lastPageSize),
    pageSize,
  )
  def combine(other: Cursor): Cursor   = if queryId == other.queryId then other.next(other.offset)
  else this

  def withTotal(newTotal: Max[Long]): Cursor =
    copy(total = total.map(_.maximum(newTotal)).orElse(Some(newTotal)))

  def withQueryId(newQueryId: Option[UUID]): Cursor = copy(queryId = newQueryId)

object Cursor:
  val emptyQueryId = UUID.fromString("00000000-0000-0000-0000-000000000000")
  given Monoid[Cursor] with
    def identity: Cursor                            = Cursor(None, 0L, None, 0L)
    def combine(a: => Cursor, b: => Cursor): Cursor =
      if a.queryId == b.queryId |
          b.queryId.contains(emptyQueryId) |
          a.queryId.contains(emptyQueryId)
      then
        a.next(b.offset)
          .withQueryId(
            Seq(a.queryId, b.queryId).flatten
              .filter(_ != emptyQueryId)
              .headOption
          )
      else b.total.fold(a)(a.withTotal).next(b.offset)

  case class Patch(offset: Long, total: Option[Max[Long]])

  val differ: Differ[Cursor, Patch] = new Differ[Cursor, Patch] {

    /**
     * Combines two patches to produce a new patch that describes the updates of
     * the first patch and then the updates of the second patch. The combine
     * operation should be associative. In addition, if the combine operation is
     * commutative then joining multiple fibers concurrently will result in
     * deterministic `FiberRef` values.
     */
    def combine(first: Patch, second: Patch): Patch =
      Patch(
        first.offset + second.offset,
        first.total
          .as(first)
          .zipWith(second.total.as(second)) { (a, b) =>
            (a.total
              .zipWith(b.total)(_ min _))
              .flatMap { t =>
                Some(t).filter(_ > a.offset + second.offset)
              }
              .orElse(first.total.orElse(second.total))
          }
          .flatten,
      )

    /**
     * Constructs a patch describing the updates to a value from an old value and
     * a new value.
     */
    def diff(oldValue: Cursor, newValue: Cursor): Patch =
      Patch(newValue.offset - oldValue.offset, newValue.total.filter(_ > newValue.offset))

    /**
     * An empty patch that describes no changes.
     */
    def empty: Patch = Patch(0L, None)

    /**
     * Applies a patch to an old value to produce a new value that is equal to the
     * old value with the updates described by the patch.
     */
    def patch(patch: Patch)(oldValue: Cursor): Cursor =
      oldValue
        .next(patch.offset)
        .withTotal(patch.total.getOrElse(oldValue.total.getOrElse(Max(0L))))
  }

  private[pg] object ref:
    val cursorRef: FiberRef[Cursor] = Unsafe.unsafe { implicit u =>
      FiberRef.unsafe.makePatch(
        initial,
        Cursor.differ,
        Patch(0L, None),
        (a, b) => (a.combine(b)),
      )
    }

  val initial: Cursor = Cursor(None, 0L, None, 100L)

trait BlobStoreRepo:
  def upsert(row: BlobStoreRow): Task[Int]
  def get(key: StoreKey): Task[Option[BlobStoreRow]]
  def listActive(cursor: Option[Cursor] = None): ZStream[Any, Throwable, BlobStoreRow]

final class BlobStoreRepoLive(xa: TransactorZIO) extends BlobStoreRepo:

  def upsert(row: BlobStoreRow): Task[Int] =
    xa.transact {
      sql"""
        INSERT INTO blob_store (key, impl_id, build_fp, dv_schema_urn, dv_canonical_bin, dv_json_preview, status)
        VALUES (${row.key}, ${row.implId}, ${row.buildFp}, ${row.dvSchemaUrn}, ${row.dvCanonical}, ${row.dvJsonPreview}, ${row.status.toDbValue})
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

  def listActive(cursor: Option[Cursor] = None): ZStream[Any, Throwable, BlobStoreRow] =
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
        rows         = ZStream.paginateChunkZIO(0) { offset =>
                         for {
                           totalNow <- total.get
                           rows     <- xa.transact {
                                         val rows = sql"""
              SELECT count(*) as total, key, impl_id, build_fp, dv_schema_urn, dv_canonical_bin, dv_json_preview, status, version
              FROM blob_store WHERE status = ${StoreStatus.Active.toDbValue}
              ORDER BY updated_at DESC
              Limit $limit
              OFFSET $offset
            """.query[(Long, BlobStoreRow)].run()

                                         val newTotal  = rows.headOption.map(_._1).orElse(totalNow.total).getOrElse(0L)
                                         val newOffset = offset + rows.size

                                         Chunk.fromIterable(rows.view.map(_._2)) -> (newTotal, newOffset)

                                       }.flatMap { case (rows, (newTotal, newOffset)) =>
                                         for
                                           current <- total.get
                                           _       <- total.set(current.withTotal(Max(newTotal)).next(newOffset))
                                         yield (
                                           rows,
                                           Some(newOffset).filter(_ => newOffset < limit && current.total.getOrElse(Max(0L)) < Max(newTotal)),
                                         )
                                       }
                         } yield rows
                       }
      yield rows
    }

object BlobStoreRepoLive:
  val layer: ZLayer[TransactorZIO, Nothing, BlobStoreRepo] =
    ZLayer.fromFunction(new BlobStoreRepoLive(_))

// ---- Block repository -------------------------------------------------------

trait BlockRepo:

  def upsertBlock(
    key: BlockKey,
    size: PosLong,
    inline: Option[SmallBytes],
  ): Task[Int]

  def getHead(key: BlockKey): Task[Option[(PosLong, Boolean)]]

  def linkLocation(
    key: BlockKey,
    storeKey: StoreKey,
    uri: Option[String],
    len: PosLong,
    etag: Option[String],
    storageClass: Option[String],
  ): Task[Int]

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
        (s.refineUnsafe[Positive], b)
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
  val layer =
    ZLayer.derive[BlockRepoLive]

// ---- File repository --------------------------------------------------------

trait FileRepo:

  def upsertFile(key: FileKey): Task[UUID]

  def putFileBlocks(
    fileId: UUID
  ): ZPipeline[Any, Throwable, BlockInsert, BlockKey]

  def findFileId(key: FileKey): Task[Option[UUID]]

final class FileRepoLive(xa: TransactorZIO) extends FileRepo:
  def upsertFile(key: FileKey): Task[UUID] =
    Random.nextUUID.flatMap(id =>
      xa.transact {
        sql"""
          INSERT INTO file (id, algo_id, hash, size_bytes, media_type)
          VALUES ($id, ${key.algoId}, ${key.hash}, ${key.size}, ${key.mediaType})
          ON CONFLICT (algo_id, hash, size_bytes) DO UPDATE
          SET media_type = EXCLUDED.media_type
          RETURNING id
        """.query[UUID].run().head
      }
    )

  def putFileBlocks(
    fileId: UUID
  ): ZPipeline[Any, Throwable, BlockInsert, BlockKey] =
    ZPipeline.fromFunction((s: ZStream[Any, Throwable, BlockInsert]) =>
      s.zipWithIndex.mapChunksZIO { case chunk =>
        chunk
          .mapZIO { case ((bk, offset, len), idx) =>
            xa.transact {
              sql"""
                  INSERT INTO file_block (file_id, seq, block_algo_id, block_hash, offset_bytes, length_bytes)
                  VALUES ($fileId, $idx, ${bk.algoId}, ${bk.hash}, $offset, $len)
                """.returning[BlockKey].run()
            }.map(Chunk.fromIterable)
          }
          .map(_.flatten)
      }
    )

  def findFileId(key: FileKey): Task[Option[UUID]] =
    xa.transact {
      sql"""
        SELECT id FROM file
        WHERE algo_id = ${key.algoId} AND hash = ${key.hash} AND size_bytes = ${key.size}
      """.query[UUID].run().headOption
    }

object FileRepoLive:
  val layer: ZLayer[TransactorZIO, Nothing, FileRepo] =
    ZLayer.derive[FileRepoLive]
