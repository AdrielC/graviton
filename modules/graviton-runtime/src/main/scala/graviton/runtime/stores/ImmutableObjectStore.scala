package graviton.runtime.stores

import graviton.core.locator.BlobLocator
import zio.stream.ZStream
import zio.ZIO

trait ImmutableObjectStore:
  def head(locator: BlobLocator): ZIO[Any, Throwable, Option[Long]]
  def list(prefix: String): ZStream[Any, Throwable, BlobLocator]
  def get(locator: BlobLocator): ZStream[Any, Throwable, Byte]
