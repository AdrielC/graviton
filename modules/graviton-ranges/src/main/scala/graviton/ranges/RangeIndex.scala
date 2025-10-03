package graviton.ranges

import cats.collections.{Diet, Range as IncRange}
import cats.instances.long.given
import graviton.ranges.Ranges.*

final case class RangeIndex private (diet: Diet[Long]):

  def add(range: ByteRange): RangeIndex =
    RangeIndex(diet.addRange(RangeIndex.toInclusive(range)))

  def addAll(ranges: Iterable[ByteRange]): RangeIndex =
    ranges.foldLeft(this)(_.add(_))

  def remove(range: ByteRange): RangeIndex =
    RangeIndex(diet.removeRange(RangeIndex.toInclusive(range)))

  def contains(range: ByteRange): Boolean =
    diet.containsRange(RangeIndex.toInclusive(range))

  def containsPoint(index: ByteIndex): Boolean =
    diet.contains(index)

  def intervals: List[ByteRange] =
    diet.toIterator.map { inc =>
      val endExclusive = inc.end + 1
      ByteRange.unsafe(inc.start, endExclusive)
    }.toList

  def min: Option[ByteIndex] =
    diet.min.map(ByteIndex.unsafe)

  def maxExclusive: Option[ByteIndex] =
    diet.max.map(n => ByteIndex.unsafe(n + 1))

  def union(that: RangeIndex): RangeIndex =
    RangeIndex(this.diet ++ that.diet)

  def intersect(that: RangeIndex): RangeIndex =
    RangeIndex(this.diet & that.diet)

  def difference(that: RangeIndex): RangeIndex =
    RangeIndex(this.diet -- that.diet)

  def holes(total: Length): List[ByteRange] =
    val totalValue = total
    if totalValue == 0 then Nil
    else
      val requested = Diet.fromRange(IncRange(0L, totalValue - 1))
      val missing   = requested -- diet
      missing.toIterator.map { inc =>
        val endExclusive = inc.end + 1
        ByteRange.unsafe(inc.start, endExclusive)
      }.toList

  def isComplete(total: Length): Boolean =
    holes(total).isEmpty

object RangeIndex:
  val empty: RangeIndex = RangeIndex(Diet.empty[Long])

  def fromRanges(ranges: Iterable[ByteRange]): RangeIndex =
    RangeIndex.empty.addAll(ranges)

  private def toInclusive(range: ByteRange): IncRange[Long] =
    IncRange(range.start, range.endExclusive - 1)
