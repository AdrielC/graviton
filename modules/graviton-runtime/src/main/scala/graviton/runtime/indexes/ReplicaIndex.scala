package graviton.runtime.indexes

import graviton.core.keys.BinaryKey
import graviton.core.locator.BlobLocator
import graviton.runtime.stores.StoreError
import zio.IO

trait ReplicaIndex:
  def replicas(key: BinaryKey): IO[StoreError, Set[BlobLocator]]
  def update(key: BinaryKey, locators: Set[BlobLocator]): IO[StoreError, Unit]
