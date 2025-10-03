package graviton.server.wiring

import graviton.runtime.model.{BlobStat, BlobWriteResult}
import graviton.runtime.stores.BlobStore
import zio.stream.{ZSink, ZStream}
import zio.{Layer, ZIO, ZLayer}

object Composition:
  private final class NoopBlobStore extends BlobStore:
    override def put: ZSink[Any, Throwable, Byte, Nothing, BlobWriteResult]                     =
      ZSink.fail(new UnsupportedOperationException("BlobStore wiring not configured"))
    override def get(key: graviton.core.keys.BinaryKey): ZStream[Any, Throwable, Byte]          = ZStream.empty
    override def stat(key: graviton.core.keys.BinaryKey): ZIO[Any, Throwable, Option[BlobStat]] = ZIO.succeed(None)
    override def delete(key: graviton.core.keys.BinaryKey): ZIO[Any, Throwable, Unit]           = ZIO.unit

  val live: Layer[Nothing, BlobStore] = ZLayer.succeed(new NoopBlobStore)
