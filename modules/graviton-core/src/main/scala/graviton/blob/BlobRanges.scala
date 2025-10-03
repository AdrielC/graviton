package graviton.blob

import graviton.ranges.ByteRange
import graviton.ranges.Ranges.Length
import zio.*

type TotalSizeLookup = BinaryKey => UIO[Length]

trait BlobRanges:
  def query(key: BinaryKey): UIO[(Boolean, List[ByteRange])]
  def holesFor(key: BinaryKey, on: BlobLocator): UIO[List[ByteRange]]
  def markPresent(on: BlobLocator, range: ByteRange): UIO[Unit]
  def present(on: BlobLocator): UIO[List[ByteRange]]

object BlobRanges:
  def live(
    rangeTracker: RangeTracker,
    replicaIndex: ReplicaIndex,
    totalSize: TotalSizeLookup,
  ): BlobRanges =
    new Live(rangeTracker, replicaIndex, totalSize)

  private final class Live(
    rangeTracker: RangeTracker,
    replicaIndex: ReplicaIndex,
    totalSize: TotalSizeLookup,
  ) extends BlobRanges:

    def query(key: BinaryKey): UIO[(Boolean, List[ByteRange])] =
      totalSize(key).flatMap(replicaIndex.completeness(key, _))

    def holesFor(key: BinaryKey, on: BlobLocator): UIO[List[ByteRange]] =
      for
        total <- totalSize(key)
        idx   <- rangeTracker.summary(on)
      yield idx.holes(total)

    def markPresent(on: BlobLocator, range: ByteRange): UIO[Unit] =
      rangeTracker.add(on, range)

    def present(on: BlobLocator): UIO[List[ByteRange]] =
      rangeTracker.summary(on).map(_.intervals)
