package graviton.backend.s3

import graviton.core.keys.BinaryKey
import graviton.runtime.model.{BlobStat, BlobWriteResult}
import graviton.runtime.stores.BlobStore
import zio.stream.ZStream
import zio.ZIO

class S3BlobStore extends BlobStore:
  override def put(bytes: ZStream[Any, Throwable, Byte]): ZIO[Any, Throwable, BlobWriteResult] =
    ZIO.dieMessage("not implemented")
  override def get(key: BinaryKey): ZStream[Any, Throwable, Byte]                              = ZStream.empty
  override def stat(key: BinaryKey): ZIO[Any, Throwable, Option[BlobStat]]                     = ZIO.succeed(None)
  override def delete(key: BinaryKey): ZIO[Any, Throwable, Unit]                               = ZIO.unit
