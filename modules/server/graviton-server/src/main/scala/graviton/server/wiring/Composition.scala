package graviton.server.wiring

import graviton.runtime.model.{BlobStat, BlobWritePlan, BlobWriteResult}
import graviton.runtime.stores.BlobStore
import zio.stream.*
import zio.*

object Composition:
  private final class NoopBlobStore extends BlobStore:
    override def put(plan: BlobWritePlan): ZSink[Any, Throwable, Byte, Chunk[Byte], BlobWriteResult] =
      ZSink.fail(new UnsupportedOperationException("BlobStore wiring not configured"))
    override def get(key: graviton.core.keys.BinaryKey): ZStream[Any, Throwable, Byte]               = ZStream.empty
    override def stat(key: graviton.core.keys.BinaryKey): ZIO[Any, Throwable, Option[BlobStat]]      = ZIO.succeed(None)
    override def delete(key: graviton.core.keys.BinaryKey): ZIO[Any, Throwable, Unit]                = ZIO.unit

  val live: Layer[Nothing, BlobStore] = ZLayer.succeed(new NoopBlobStore)
