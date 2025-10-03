package graviton.blob

import graviton.ranges.{ByteRange, RangeIndex}
import graviton.ranges.Ranges.Length
import graviton.ranges.collections.DisjointSets
import zio.*

trait ReplicaIndex:
  def union(key: BinaryKey, a: BlobLocator, b: BlobLocator): UIO[Unit]
  def replicas(key: BinaryKey): UIO[Set[BlobLocator]]
  def completeness(key: BinaryKey, total: Length): UIO[(Boolean, List[ByteRange])]

object ReplicaIndex:
  def inMemory(rangeTracker: RangeTracker): UIO[ReplicaIndex] =
    Ref
      .make(Map.empty[BinaryKey, DisjointSets[BlobLocator]])
      .map(new InMemoryReplicaIndex(rangeTracker, _))

  private final class InMemoryReplicaIndex(
    rangeTracker: RangeTracker,
    state: Ref[Map[BinaryKey, DisjointSets[BlobLocator]]],
  ) extends ReplicaIndex:

    def union(key: BinaryKey, a: BlobLocator, b: BlobLocator): UIO[Unit] =
      state.update { existing =>
        val dsu      = existing.getOrElse(key, DisjointSets.empty[BlobLocator])
        val combined = dsu.union(a, b)
        existing.updated(key, combined)
      }

    def replicas(key: BinaryKey): UIO[Set[BlobLocator]] =
      state.modify { existing =>
        val dsu                                                = existing.getOrElse(key, DisjointSets.empty[BlobLocator])
        val (compressed, members)                              = dsu.allMembers
        val updated: Map[BinaryKey, DisjointSets[BlobLocator]] =
          if members.isEmpty then existing
          else existing.updated(key, compressed)
        (members, updated)
      }

    def completeness(key: BinaryKey, total: Length): UIO[(Boolean, List[ByteRange])] =
      for
        members <- replicas(key)
        indexes <- ZIO.foreach(members)(rangeTracker.summary)
        combined = indexes.foldLeft(RangeIndex.empty)(_.union(_))
        holes    = combined.holes(total)
      yield (holes.isEmpty, holes)
