package graviton.runtime.stores

import graviton.core.locator.BlobLocator
import zio.*
import zio.stream.*

trait MutableObjectStore extends ImmutableObjectStore:
  def put(locator: BlobLocator): ZSink[Any, Throwable, Byte, Nothing, Unit]
  def delete(locator: BlobLocator): ZIO[Any, Throwable, Unit]
  def copy(src: BlobLocator, dest: BlobLocator): ZIO[Any, Throwable, Unit]
