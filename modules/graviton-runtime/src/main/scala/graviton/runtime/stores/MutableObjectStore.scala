package graviton.runtime.stores

import graviton.core.locator.BlobLocator
import zio.*
import zio.stream.*

trait MutableObjectStore extends ImmutableObjectStore:
  def put(locator: BlobLocator): ZSink[Any, StoreError, Byte, Nothing, Unit]
  def delete(locator: BlobLocator): IO[StoreError, Unit]
  def copy(src: BlobLocator, dest: BlobLocator): IO[StoreError, Unit]
