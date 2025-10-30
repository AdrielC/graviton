package graviton.backend.pg

import graviton.core.locator.BlobLocator
import graviton.runtime.stores.MutableObjectStore
import zio.stream.ZStream
import zio.ZIO

final class PgMutableObjectStore extends PgImmutableObjectStore with MutableObjectStore:
  override def put(locator: BlobLocator, bytes: ZStream[Any, Throwable, Byte]): ZIO[Any, Throwable, Unit] = ZIO.unit
  override def delete(locator: BlobLocator): ZIO[Any, Throwable, Unit]                                    = ZIO.unit
  override def copy(src: BlobLocator, dest: BlobLocator): ZIO[Any, Throwable, Unit]                       = ZIO.unit
