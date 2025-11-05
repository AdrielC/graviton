package graviton.runtime.indexes

import graviton.core.keys.BinaryKey
import graviton.core.locator.BlobLocator
import zio.ZIO

trait ReplicaIndex:
  def replicas(key: BinaryKey): ZIO[Any, Throwable, Set[BlobLocator]]
  def update(key: BinaryKey, locators: Set[BlobLocator]): ZIO[Any, Throwable, Unit]
