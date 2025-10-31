package graviton.core.ranges

final case class RangeSet[A: Ordering: DiscreteDomain] private (private[ranges] val normalized: Vector[Span[A]]):

  def spans: Vector[Span[A]] = normalized

  def isEmpty: Boolean = normalized.isEmpty

  def nonEmpty: Boolean = normalized.nonEmpty

  def iterator: Iterator[Span[A]] = normalized.iterator

  def toVector: Vector[Span[A]] = normalized

  def toList: List[Span[A]] = normalized.toList

  def headOption: Option[Span[A]] = normalized.headOption

  def add(span: Span[A]): RangeSet[A] = RangeSet.fromSpans(normalized :+ span)

  def union(other: RangeSet[A]): RangeSet[A] = RangeSet.fromSpans(normalized ++ other.normalized)

  def contains(value: A): Boolean = normalized.exists(_.contains(value))

  def intersect(other: RangeSet[A]): RangeSet[A] =
    val ord     = summon[Ordering[A]]
    val builder = Vector.newBuilder[Span[A]]
    var i       = 0
    var j       = 0

    while i < normalized.length && j < other.normalized.length do
      val left  = normalized(i)
      val right = other.normalized(j)
      left.intersection(right).foreach(builder += _)

      if ord.lt(left.endInclusive, right.endInclusive) then i += 1
      else if ord.lt(right.endInclusive, left.endInclusive) then j += 1
      else
        i += 1
        j += 1

    RangeSet.fromSpans(builder.result())

  def difference(other: RangeSet[A]): RangeSet[A] =
    if other.isEmpty then this
    else
      val builder = Vector.newBuilder[Span[A]]
      normalized.foreach { span =>
        val residuals = other.normalized.foldLeft(Vector(span)) { (acc, remove) =>
          acc.flatMap(_.subtract(remove))
        }
        builder ++= residuals
      }
      RangeSet.fromSpans(builder.result())

  def complement(universe: Span[A]): RangeSet[A] = RangeSet.single(universe).difference(this)

object RangeSet:
  def empty[A: Ordering: DiscreteDomain]: RangeSet[A] = new RangeSet(Vector.empty)

  def single[A: Ordering: DiscreteDomain](span: Span[A]): RangeSet[A] = new RangeSet(Vector(span))

  def fromSpans[A: Ordering: DiscreteDomain](spans: Iterable[Span[A]]): RangeSet[A] =
    val collected = spans.iterator.toVector
    if collected.isEmpty then empty
    else new RangeSet(normalize(collected))

  private def normalize[A: Ordering: DiscreteDomain](spans: Vector[Span[A]]): Vector[Span[A]] =
    val ord    = summon[Ordering[Span[A]]]
    val sorted = spans.sorted(using ord)
    if sorted.isEmpty then Vector.empty
    else
      val builder = Vector.newBuilder[Span[A]]
      var current = sorted.head
      sorted.tail.foreach { span =>
        if current.canMerge(span) then current = current.merge(span)
        else
          builder += current
          current = span
      }
      builder += current
      builder.result()

  extension [A: Ordering: DiscreteDomain](set: RangeSet[A]) def toTree: RangeTree[A] = RangeTree.fromRangeSet(set)
