package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.core.types.{BlobOffset, FileSize}
import graviton.runtime.model.{
  BlobDescription,
  BlobListing,
  BlobStat,
  BlobWritePlan,
  BlobWriteResult,
  InventoryCursor,
  InventoryPage,
  InventoryPageSize,
}
import zio.*
import zio.stream.*

trait BlobStore:
  type BlobSink = ZSink[Any, StoreError, Byte, Chunk[Byte], BlobWriteResult]

  /**
   * Persist a logical blob by streaming its bytes along with manifest + attribute metadata.
   * The sink may emit leftover bytes when callers need to replay data across chunker boundaries.
   */
  def put(plan: BlobWritePlan = BlobWritePlan()): BlobSink

  /** Retrieve the bytes for a blob by logical key (reassembling blocks as needed). */
  def get(key: BinaryKey.Blob): ZStream[Any, StoreError, Byte]

  /**
   * Retrieve a non-empty half-open byte range without requiring callers to
   * discard the preceding blob bytes. CAS implementations override this to
   * fetch only manifest blocks that intersect the requested range.
   */
  def getRange(
    key: BinaryKey.Blob,
    start: BlobOffset,
    length: FileSize,
  ): ZStream[Any, StoreError, Byte] =
    get(key).chunks
      .mapAccum(start.value: Long) { (remaining, chunk) =>
        val dropped = math.min(remaining, chunk.length.toLong).toInt
        (remaining - dropped.toLong) -> chunk.drop(dropped)
      }
      .flatMap(ZStream.fromChunk)
      .take(length.value)

  /** Return metadata (size, etag, timestamps) when supported by the backend. */
  def stat(key: BinaryKey.Blob): IO[StoreError, Option[BlobStat]]

  /** Return one bounded page in the backend's stable native inventory order. */
  def inventoryPage(
    after: Option[InventoryCursor],
    limit: InventoryPageSize = InventoryPageSize.Default,
  ): IO[StoreError, InventoryPage[BlobListing]]

  /** Stream the complete inventory without collecting repository-scale state. */
  final def streamInventory: ZStream[Any, StoreError, BlobListing] =
    ZStream
      .paginateChunkZIO(Option.empty[InventoryCursor]) { cursor =>
        inventoryPage(cursor, InventoryPageSize.Maximum).map { page =>
          page.items -> page.next.map(Some(_))
        }
      }

  /** Inspect the persisted manifest for a logical blob. */
  def inspect(key: BinaryKey.Blob): IO[StoreError, Option[BlobDescription]]

  /** Remove the blob and any associated manifest/attribute entries. */
  def delete(key: BinaryKey.Blob): IO[StoreError, Unit]

  /** Readiness probe for every backing service required by this store. */
  def healthCheck: IO[StoreError, Unit]

object BlobStore:
  val service: ZIO[BlobStore, Nothing, BlobStore] = ZIO.service[BlobStore]
