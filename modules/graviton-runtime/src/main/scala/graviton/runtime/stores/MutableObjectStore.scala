package graviton.runtime.stores

import graviton.core.locator.BlobLocator
import zio.stream.ZStream
import zio.ZIO

trait MutableObjectStore extends ImmutableObjectStore:
  def put(locator: BlobLocator, bytes: ZStream[Any, Throwable, Byte]): ZIO[Any, Throwable, Unit]
  def delete(locator: BlobLocator): ZIO[Any, Throwable, Unit]
  def copy(src: BlobLocator, dest: BlobLocator): ZIO[Any, Throwable, Unit]
