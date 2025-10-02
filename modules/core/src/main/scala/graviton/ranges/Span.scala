package graviton.ranges

import cats.kernel.Order

/** End-exclusive span [start, end). */
final case class Span[A](start: A, endExclusive: A):
  def isEmpty(using order: Order[A]): Boolean = order.gteqv(start, endExclusive)

object Span:
  def toInterval[A](span: Span[A]): Interval[A] =
    Interval(Bound.Closed(span.start), Bound.Open(span.endExclusive))

  def toInclusiveRange[A](span: Span[A])(using domain: DiscreteDomain[A]): Option[cats.collections.Range[A]] =
    if span.isEmpty(using domain.order) then None
    else domain.prev(span.endExclusive).map(endIncl => cats.collections.Range(span.start, endIncl))

  def fromClosed[A](lower: A, upper: A)(using domain: DiscreteDomain[A]): Option[Span[A]] =
    domain.next(upper).map(endExcl => Span(lower, endExcl))
