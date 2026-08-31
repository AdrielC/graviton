package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.core.types.{BlobOffset, FileSize}
import graviton.runtime.model.{
  BlobBlockDescription,
  BlobDescription,
  BlobInspectionPage,
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

  /**
   * Stream manifest block descriptions with downstream back pressure.
   *
   * The compatibility implementation delegates to the historical materialized
   * inspection method. Built-in production stores override it without loading
   * the complete manifest.
   */
  def streamBlockDescriptions(key: BinaryKey.Blob): ZStream[Any, StoreError, BlobBlockDescription] =
    ZStream
      .fromZIO(inspect(key).someOrFail(StoreError.NotFound(StoreOperation.InspectBlob, key)))
      .flatMap(description => ZStream.fromChunk(description.blocks))

  /** Return one bounded manifest page tied to the requested blob identity. */
  def inspectPage(
    key: BinaryKey.Blob,
    after: Option[InventoryCursor],
    limit: InventoryPageSize = InventoryPageSize.Default,
  ): IO[StoreError, Option[BlobInspectionPage]] =
    inspect(key).flatMap {
      case None              => ZIO.none
      case Some(description) =>
        ZIO
          .fromEither(BlobStore.decodeManifestCursor(key, after))
          .mapError(StoreError.InvalidInput(StoreOperation.InspectBlob, _))
          .flatMap { offset =>
            val available = description.blocks.drop(offset)
            val page      = available.take(limit.value)
            val nextIndex = offset.toLong + page.length.toLong
            ZIO
              .foreach(Option.when(available.length > limit.value)(nextIndex))(index =>
                ZIO
                  .fromEither(BlobStore.encodeManifestCursor(key, index))
                  .mapError(StoreError.InvalidInput(StoreOperation.InspectBlob, _))
              )
              .map(next => Some(BlobInspectionPage(description.listing, page, next)))
          }
    }

  /** Read bounded semantic metadata persisted with the manifest. */
  def metadata(key: BinaryKey.Blob): IO[StoreError, Option[BlobMetadataV1]] =
    ZIO.succeed(key).as(Option.empty[BlobMetadataV1])

  /** Remove the blob and any associated manifest/attribute entries. */
  def delete(key: BinaryKey.Blob): IO[StoreError, Unit]

  /** Readiness probe for every backing service required by this store. */
  def healthCheck: IO[StoreError, Unit]

object BlobStore:
  val service: ZIO[BlobStore, Nothing, BlobStore] = ZIO.service[BlobStore]

  private[graviton] def encodeManifestCursor(key: BinaryKey.Blob, nextIndex: Long): Either[String, InventoryCursor] =
    Either
      .cond(nextIndex >= 0L, (), "manifest cursor index must be non-negative")
      .flatMap(_ => InventoryCursor.encode(graviton.runtime.model.InventoryNamespace.Manifest, s"${key.bits.render}|$nextIndex"))

  private[graviton] def decodeManifestCursor(
    key: BinaryKey.Blob,
    cursor: Option[InventoryCursor],
  ): Either[String, Int] =
    cursor match
      case None        => Right(0)
      case Some(value) =>
        for
          decoded <- InventoryCursor.decode(value, graviton.runtime.model.InventoryNamespace.Manifest)
          split   <- decoded.lastIndexOf('|') match
                       case index if index > 0 && index < decoded.length - 1 => Right(index)
                       case _                                                => Left("manifest cursor payload is malformed")
          blob     = decoded.substring(0, split)
          rawIndex = decoded.substring(split + 1)
          _       <- Either.cond(blob == key.bits.render, (), "manifest cursor belongs to a different blob")
          index   <- rawIndex.toLongOption.toRight("manifest cursor index is not a decimal integer")
          _       <- Either.cond(index >= 0L && index <= Int.MaxValue.toLong, (), "manifest cursor index is outside the supported range")
        yield index.toInt
