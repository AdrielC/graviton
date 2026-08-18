package graviton.runtime.stores

import graviton.core.locator.BlobLocator
import zio.*
import zio.stream.*

trait ImmutableObjectStore:
  def head(locator: BlobLocator): ZIO[Any, Throwable, Option[Long]]
  def list(prefix: String): ZStream[Any, Throwable, BlobLocator]
  def get(locator: BlobLocator): ZStream[Any, Throwable, Byte]
