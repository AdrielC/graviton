package graviton

import zio.*
import zio.stream.*

trait BlobStore:
  def id: BlobStoreId
  def status: UIO[BlobStoreStatus]
  def read(key: BlockKey): IO[Throwable, Option[Bytes]]
  def write(key: BlockKey, data: Bytes): IO[Throwable, Unit]
  def delete(key: BlockKey): IO[Throwable, Boolean]
