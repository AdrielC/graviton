package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.runtime.model.{BlobStat, BlobWriteResult}
import zio.stream.ZStream
import zio.ZIO

trait BlobStore:
  def put(bytes: ZStream[Any, Throwable, Byte]): ZIO[Any, Throwable, BlobWriteResult]
  def get(key: BinaryKey): ZStream[Any, Throwable, Byte]
  def stat(key: BinaryKey): ZIO[Any, Throwable, Option[BlobStat]]
  def delete(key: BinaryKey): ZIO[Any, Throwable, Unit]
