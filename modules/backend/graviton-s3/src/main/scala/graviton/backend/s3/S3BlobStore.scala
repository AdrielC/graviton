package graviton.backend.s3

import graviton.core.keys.BinaryKey
import graviton.runtime.model.{BlobStat, BlobWritePlan, BlobWriteResult}
import graviton.runtime.stores.BlobStore
import zio.*
import zio.stream.*

class S3BlobStore extends BlobStore:
  override def put(plan: BlobWritePlan): ZSink[Any, Throwable, Byte, Chunk[Byte], BlobWriteResult] =
    ZSink.fail(new UnsupportedOperationException("S3BlobStore.put not implemented"))

  override def get(key: BinaryKey): ZStream[Any, Throwable, Byte] = ZStream.empty

  override def stat(key: BinaryKey): ZIO[Any, Throwable, Option[BlobStat]] = ZIO.succeed(None)

  override def delete(key: BinaryKey): ZIO[Any, Throwable, Unit] = ZIO.unit
