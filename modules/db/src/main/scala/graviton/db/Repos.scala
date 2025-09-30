package graviton.db

import zio.*
import zio.stream.*

trait BlobStoreRepo:
  def upsert(row: BlobStoreRow): Task[Int]
  def get(key: StoreKey): Task[Option[BlobStoreRow]]
  def listActive(cursor: Option[Cursor] = None): ZStream[Any, Throwable, BlobStoreRow]

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

trait BlobRepo:
  def upsertBlob(key: BlobKey): Task[java.util.UUID]

  def putBlobBlocks(
    blobId: java.util.UUID
  ): ZPipeline[Any, Throwable, BlockInsert, BlockKey]

  def findBlobId(key: BlobKey): Task[Option[java.util.UUID]]
