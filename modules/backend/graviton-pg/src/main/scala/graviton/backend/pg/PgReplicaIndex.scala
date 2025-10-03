package graviton.backend.pg

import graviton.core.keys.BinaryKey
import graviton.core.locator.BlobLocator
import graviton.runtime.indexes.ReplicaIndex
import zio.ZIO

final class PgReplicaIndex extends ReplicaIndex:
  override def replicas(key: BinaryKey): ZIO[Any, Throwable, Set[BlobLocator]]               = ZIO.succeed(Set.empty)
  override def update(key: BinaryKey, locators: Set[BlobLocator]): ZIO[Any, Throwable, Unit] = ZIO.unit
