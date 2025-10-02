package graviton.ranges

import cats.collections.{Diet, Range as IncRange}
import cats.kernel.Order

final case class RangeSet[A] private[ranges] (diet: Diet[A]):
  def add(span: Span[A])(using domain: DiscreteDomain[A]): RangeSet[A] =
    given order: Order[A]                        = domain.order
    given discrete: cats.collections.Discrete[A] = RangeSet.discreteFromDomain(using domain)
    val _                                        = summon[Order[A]]
    val _                                        = summon[cats.collections.Discrete[A]]
    Span.toInclusiveRange(span).fold(this)(range => RangeSet(diet.addRange(range)))

  def addAll(spans: Iterable[Span[A]])(using domain: DiscreteDomain[A]): RangeSet[A] =
    spans.foldLeft(this)(_.add(_))

  def remove(span: Span[A])(using domain: DiscreteDomain[A]): RangeSet[A] =
    given order: Order[A]                        = domain.order
    given discrete: cats.collections.Discrete[A] = RangeSet.discreteFromDomain(using domain)
    val _                                        = summon[Order[A]]
    val _                                        = summon[cats.collections.Discrete[A]]
    Span.toInclusiveRange(span).fold(this)(range => RangeSet(diet.removeRange(range)))

  def contains(span: Span[A])(using domain: DiscreteDomain[A]): Boolean =
    given order: Order[A]                        = domain.order
    given discrete: cats.collections.Discrete[A] = RangeSet.discreteFromDomain(using domain)
    val _                                        = summon[Order[A]]
    val _                                        = summon[cats.collections.Discrete[A]]
    Span.toInclusiveRange(span).exists(diet.containsRange)

  def containsPoint(value: A)(using domain: DiscreteDomain[A]): Boolean =
    given order: Order[A] = domain.order
    val _                 = summon[Order[A]]
    diet.contains(value)

  def intervals(using domain: DiscreteDomain[A]): List[Span[A]] =
    given order: Order[A]                        = domain.order
    given discrete: cats.collections.Discrete[A] = RangeSet.discreteFromDomain(using domain)
    val _                                        = summon[Order[A]]
    val _                                        = summon[cats.collections.Discrete[A]]
    diet.toIterator.toList.flatMap { inclusive =>
      domain.next(inclusive.end).map(endExcl => Span(inclusive.start, endExcl))
    }

  def union(that: RangeSet[A])(using domain: DiscreteDomain[A]): RangeSet[A] =
    given order: Order[A]                        = domain.order
    given discrete: cats.collections.Discrete[A] = RangeSet.discreteFromDomain(using domain)
    val _                                        = summon[Order[A]]
    val _                                        = summon[cats.collections.Discrete[A]]
    RangeSet(this.diet ++ that.diet)

  def intersect(that: RangeSet[A])(using domain: DiscreteDomain[A]): RangeSet[A] =
    given order: Order[A]                        = domain.order
    given discrete: cats.collections.Discrete[A] = RangeSet.discreteFromDomain(using domain)
    val _                                        = summon[Order[A]]
    val _                                        = summon[cats.collections.Discrete[A]]
    RangeSet(this.diet & that.diet)

  def difference(that: RangeSet[A])(using domain: DiscreteDomain[A]): RangeSet[A] =
    given order: Order[A]                        = domain.order
    given discrete: cats.collections.Discrete[A] = RangeSet.discreteFromDomain(using domain)
    val _                                        = summon[Order[A]]
    val _                                        = summon[cats.collections.Discrete[A]]
    RangeSet(this.diet -- that.diet)

  def holes(within: Interval[A])(using domain: DiscreteDomain[A]): List[Span[A]] =
    given order: Order[A]                        = domain.order
    given discrete: cats.collections.Discrete[A] = RangeSet.discreteFromDomain(using domain)
    val _                                        = summon[Order[A]]
    val _                                        = summon[cats.collections.Discrete[A]]
    within.toClosed.toList.flatMap { case (lower, upper) =>
      val desired = Diet.empty[A].addRange(IncRange(lower, upper))
      val gap     = desired -- diet
      gap.toIterator.toList.flatMap { inclusive =>
        domain.next(inclusive.end).map(endExcl => Span(inclusive.start, endExcl))
      }
    }

  def isComplete(within: Interval[A])(using domain: DiscreteDomain[A]): Boolean =
    holes(within).isEmpty

  def min(using domain: DiscreteDomain[A]): Option[A] =
    given order: Order[A] = domain.order
    val _                 = summon[Order[A]]
    diet.min

  def maxExclusive(using domain: DiscreteDomain[A]): Option[A] =
    given order: Order[A] = domain.order
    val _                 = summon[Order[A]]
    diet.max.flatMap(domain.next)

object RangeSet:
  def empty[A](using domain: DiscreteDomain[A]): RangeSet[A] =
    given order: Order[A]                        = domain.order
    given discrete: cats.collections.Discrete[A] = discreteFromDomain(using domain)
    val _                                        = summon[Order[A]]
    val _                                        = summon[cats.collections.Discrete[A]]
    RangeSet(Diet.empty)

  def fromSpans[A](spans: Iterable[Span[A]])(using domain: DiscreteDomain[A]): RangeSet[A] =
    spans.foldLeft(empty[A])(_.add(_))

  given orderFromDomain[A](using domain: DiscreteDomain[A]): Order[A] = domain.order

  given discreteFromDomain[A](using domain: DiscreteDomain[A]): cats.collections.Discrete[A] with
    override def succ(a: A): A = domain.next(a).getOrElse(a)
    override def pred(a: A): A = domain.prev(a).getOrElse(a)
