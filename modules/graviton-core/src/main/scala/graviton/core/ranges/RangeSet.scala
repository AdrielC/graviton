package graviton.core.ranges

final case class RangeSet[A: Ordering](spans: List[Span[A]]):
  def add(span: Span[A]): RangeSet[A] = RangeSet((span :: spans).sortBy(_.start))
  def contains(value: A): Boolean     =
    spans.exists { span =>
      val ord = summon[Ordering[A]]
      ord.lteq(span.start, value) && ord.lteq(value, span.end)
    }

object RangeSet:
  def empty[A: Ordering]: RangeSet[A] = RangeSet(Nil)
