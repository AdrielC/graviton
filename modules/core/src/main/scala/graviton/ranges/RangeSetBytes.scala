package graviton.ranges

import graviton.ranges.DiscreteDomain.given

object RangeSetBytes:
  def fromIntervals(intervals: Iterable[(Long, Long)]): RangeSet[Long] =
    intervals.foldLeft(RangeSet.empty[Long]) { case (acc, (start, endExclusive)) =>
      acc.add(Span(start, endExclusive))
    }

  def unionAll(values: Iterable[RangeSet[Long]]): RangeSet[Long] =
    values.foldLeft(RangeSet.empty[Long])(_.union(_))

  def intersectAll(values: Iterable[RangeSet[Long]]): RangeSet[Long] =
    values.reduceOption(_.intersect(_)).getOrElse(RangeSet.empty[Long])
