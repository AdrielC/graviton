package graviton.backend.pg

import graviton.core.locator.BlobLocator
import graviton.runtime.stores.ImmutableObjectStore
import zio.stream.ZStream
import zio.ZIO

class PgImmutableObjectStore extends ImmutableObjectStore:
  override def head(locator: BlobLocator): ZIO[Any, Throwable, Option[Long]] = ZIO.succeed(None)
  override def list(prefix: String): ZStream[Any, Throwable, BlobLocator]    = ZStream.empty
  override def get(locator: BlobLocator): ZStream[Any, Throwable, Byte]      = ZStream.empty
