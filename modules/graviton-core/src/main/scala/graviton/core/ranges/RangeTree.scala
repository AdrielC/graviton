package graviton.core.ranges

import scala.collection.immutable.TreeMap

/**
 * Persistent interval index backed by a [[TreeMap]] that keeps spans normalized while
 * offering logarithmic-time membership and nearest-gap queries. Useful when a workload
 * needs random access to coverage information instead of linear scans over a [[RangeSet]].
 */
final class RangeTree[A: Ordering: DiscreteDomain] private (private val intervals: TreeMap[A, Span[A]]):

  inline private def ordering: Ordering[A] = summon[Ordering[A]]

  /** Insert a span, merging it with any adjacent or overlapping intervals. */
  def insert(span: Span[A]): RangeTree[A] =
    val domain  = summon[DiscreteDomain[A]]
    var current = span
    var table   = intervals

    intervals.rangeTo(span.startInclusive).lastOption match
      case Some((key, existing)) if existing.canMerge(span)(using domain) =>
        current = existing.merge(span)(using domain)
        table = table - key
      case _                                                              => ()

    var tail = table.rangeFrom(current.startInclusive)
    var done = false
    while !done do
      tail.headOption match
        case Some((key, existing)) if current.canMerge(existing)(using domain) =>
          current = current.merge(existing)(using domain)
          table = table - key
          tail = table.rangeFrom(current.startInclusive)
        case _                                                                 => done = true

    new RangeTree(table + (current.startInclusive -> current))

  /** Insert all spans from the supplied iterable. */
  def insertAll(spans: Iterable[Span[A]]): RangeTree[A] = spans.foldLeft(this)((tree, span) => tree.insert(span))

  /** Number of disjoint spans tracked by this tree. */
  def size: Int = intervals.size

  /** Retrieve the underlying spans in ascending order. */
  def spans: Vector[Span[A]] = intervals.valuesIterator.toVector

  /** O(log n) membership check. */
  def contains(value: A): Boolean =
    intervals.rangeTo(value).lastOption.exists { case (_, span) => span.contains(value) }

  /** Create a [[RangeSet]] view of the stored spans. */
  def toRangeSet: RangeSet[A] = RangeSet.fromSpans(intervals.values)

  /** Total covered length across all spans. */
  def coveredLength(using Numeric[A]): A =
    val numeric = summon[Numeric[A]]
    intervals.valuesIterator.foldLeft(numeric.zero)((acc, span) => numeric.plus(acc, span.length))

  /**
   * Return the uncovered sub-ranges within the provided window. The result is normalized and
   * expressed as a [[RangeSet]].
   */
  def missingWithin(window: Span[A]): RangeSet[A] =
    val covered = RangeSet.fromSpans(
      intervals
        .rangeTo(window.endInclusive)
        .valuesIterator
        .flatMap(_.intersection(window).iterator)
        .toVector
    )
    RangeSet.single(window).difference(covered)

  /** Find the next uncovered span starting at or after the supplied value. */
  def nextGap(from: A): Option[Span[A]] =
    val domain = summon[DiscreteDomain[A]]
    intervals.rangeTo(from).lastOption match
      case Some((_, span)) if span.contains(from) =>
        val resume = domain.next(span.endInclusive)
        locateGap(resume)
      case _                                      => locateGap(from)

  private def locateGap(start: A): Option[Span[A]] =
    val domain = summon[DiscreteDomain[A]]
    val ord    = ordering
    val tail   = intervals.rangeFrom(start)

    tail.headOption match
      case Some((_, span)) if ord.lt(start, span.startInclusive) =>
        val gapEnd = domain.previous(span.startInclusive)
        Option.when(ord.lteq(start, gapEnd))(Span.unsafe(start, gapEnd))
      case Some((_, span))                                       => locateGap(domain.next(span.endInclusive))
      case None                                                  => None

object RangeTree:
  def empty[A: Ordering: DiscreteDomain]: RangeTree[A] = new RangeTree(TreeMap.empty)

  def fromRangeSet[A: Ordering: DiscreteDomain](set: RangeSet[A]): RangeTree[A] =
    new RangeTree(TreeMap.from(set.spans.map(span => span.startInclusive -> span)))
