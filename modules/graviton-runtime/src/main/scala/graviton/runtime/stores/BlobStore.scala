package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.runtime.model.{BlobStat, BlobWritePlan, BlobWriteResult}
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
  def get(key: BinaryKey): ZStream[Any, Throwable, Byte]

  /** Return metadata (size, etag, timestamps) when supported by the backend. */
  def stat(key: BinaryKey): ZIO[Any, Throwable, Option[BlobStat]]

  /** Remove the blob and any associated manifest/attribute entries. */
  def delete(key: BinaryKey): ZIO[Any, Throwable, Unit]
