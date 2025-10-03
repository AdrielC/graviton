package graviton

import graviton.ranges.ByteRange
import zio.*

trait BlobStore:
  def id: BlobStoreId

  def status: UIO[BlobStoreStatus]

  def read(
    key: BlockKey,
    range: Option[ByteRange] = None,
  ): IO[Throwable, Option[Bytes]]

  def write(key: BlockKey, data: Bytes): Task[Unit]

  def delete(key: BlockKey): IO[Throwable, Boolean]
