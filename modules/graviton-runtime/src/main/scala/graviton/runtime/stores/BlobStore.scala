package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.runtime.model.{BlobDescription, BlobListing, BlobStat, BlobWritePlan, BlobWriteResult}
import zio.*
import zio.stream.*

trait BlobStore:
  type BlobSink = ZSink[Any, Throwable, Byte, Chunk[Byte], BlobWriteResult]

  /**
   * Persist a logical blob by streaming its bytes along with manifest + attribute metadata.
   * The sink may emit leftover bytes when callers need to replay data across chunker boundaries.
   */
  def put(plan: BlobWritePlan = BlobWritePlan()): BlobSink

  /** Retrieve the bytes for a blob by logical key (reassembling blocks as needed). */
  def get(key: BinaryKey.Blob): ZStream[Any, Throwable, Byte]

  /** Return metadata (size, etag, timestamps) when supported by the backend. */
  def stat(key: BinaryKey.Blob): ZIO[Any, Throwable, Option[BlobStat]]

  /** List persisted logical blobs, newest first. */
  def list: ZIO[Any, Throwable, Chunk[BlobListing]]

  /** Inspect the persisted manifest for a logical blob. */
  def inspect(key: BinaryKey.Blob): ZIO[Any, Throwable, Option[BlobDescription]]

  /** Remove the blob and any associated manifest/attribute entries. */
  def delete(key: BinaryKey.Blob): ZIO[Any, Throwable, Unit]

  /** Readiness probe for every backing service required by this store. */
  def healthCheck: ZIO[Any, Throwable, Unit]

object BlobStore:
  val service: ZIO[BlobStore, Nothing, BlobStore] = ZIO.service[BlobStore]
