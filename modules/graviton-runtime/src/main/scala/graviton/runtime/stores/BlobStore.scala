package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.runtime.model.{BlobStat, BlobWriteResult}
import zio.ZIO
import zio.stream.{ZSink, ZStream}

trait BlobStore:
  def put: ZSink[Any, Throwable, Byte, Nothing, BlobWriteResult]
  def get(key: BinaryKey): ZStream[Any, Throwable, Byte]
  def stat(key: BinaryKey): ZIO[Any, Throwable, Option[BlobStat]]
  def delete(key: BinaryKey): ZIO[Any, Throwable, Unit]
