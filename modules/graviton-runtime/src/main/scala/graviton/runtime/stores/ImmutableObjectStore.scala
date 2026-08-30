package graviton.runtime.stores

import graviton.core.locator.BlobLocator
import zio.*
import zio.stream.*

trait ImmutableObjectStore:
  def head(locator: BlobLocator): IO[StoreError, Option[Long]]
  def list(prefix: String): ZStream[Any, StoreError, BlobLocator]
  def get(locator: BlobLocator): ZStream[Any, StoreError, Byte]
