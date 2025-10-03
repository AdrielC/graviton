package graviton.blob

import graviton.ranges.{ByteRange, RangeIndex}
import graviton.ranges.Ranges.Length
import zio.*

trait RangeTracker:
  def add(loc: BlobLocator, range: ByteRange): UIO[Unit]
  def addMany(loc: BlobLocator, ranges: Chunk[ByteRange]): UIO[Unit]
  def holes(loc: BlobLocator, total: Length): UIO[List[ByteRange]]
  def isComplete(loc: BlobLocator, total: Length): UIO[Boolean]
  def summary(loc: BlobLocator): UIO[RangeIndex]

object RangeTracker:
  def inMemory: UIO[RangeTracker] =
    Ref.make(Map.empty[BlobLocator, RangeIndex]).map(new InMemoryRangeTracker(_))

  private final class InMemoryRangeTracker(state: Ref[Map[BlobLocator, RangeIndex]]) extends RangeTracker:
    def add(loc: BlobLocator, range: ByteRange): UIO[Unit] =
      state.update { existing =>
        val idx = existing.getOrElse(loc, RangeIndex.empty).add(range)
        existing.updated(loc, idx)
      }

    def addMany(loc: BlobLocator, ranges: Chunk[ByteRange]): UIO[Unit] =
      state.update { existing =>
        val idx = ranges.foldLeft(existing.getOrElse(loc, RangeIndex.empty))(_.add(_))
        existing.updated(loc, idx)
      }

    def holes(loc: BlobLocator, total: Length): UIO[List[ByteRange]] =
      summary(loc).map(_.holes(total))

    def isComplete(loc: BlobLocator, total: Length): UIO[Boolean] =
      summary(loc).map(_.isComplete(total))

    def summary(loc: BlobLocator): UIO[RangeIndex] =
      state.get.map(_.getOrElse(loc, RangeIndex.empty))
