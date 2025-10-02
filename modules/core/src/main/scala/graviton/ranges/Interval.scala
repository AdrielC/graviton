package graviton.ranges

import cats.kernel.Order

final case class Interval[A](lower: Bound[A], upper: Bound[A]):
  def mapMonotone[B](f: A => B): Interval[B] =
    val lowerMapped = lower match
      case Bound.NegInf        => Bound.NegInf
      case Bound.PosInf        => Bound.PosInf
      case Bound.Closed(value) => Bound.Closed(f(value))
      case Bound.Open(value)   => Bound.Open(f(value))
    val upperMapped = upper match
      case Bound.NegInf        => Bound.NegInf
      case Bound.PosInf        => Bound.PosInf
      case Bound.Closed(value) => Bound.Closed(f(value))
      case Bound.Open(value)   => Bound.Open(f(value))
    Interval(lowerMapped, upperMapped)

  def intersect(that: Interval[A])(using order: Order[A]): Interval[A] =
    import Bound.*

    def maxLower(x: Bound[A], y: Bound[A]): Bound[A] = (x, y) match
      case (NegInf, other)        => other
      case (other, NegInf)        => other
      case (PosInf, _)            => PosInf
      case (_, PosInf)            => PosInf
      case (Closed(a), Closed(b)) => if order.gteqv(a, b) then x else y
      case (Open(a), Open(b))     => if order.gteqv(a, b) then x else y
      case (Open(a), Closed(b))   => if order.gt(a, b) then x else y
      case (Closed(a), Open(b))   => if order.gteqv(a, b) then x else y

    def minUpper(x: Bound[A], y: Bound[A]): Bound[A] = (x, y) match
      case (PosInf, other)        => other
      case (other, PosInf)        => other
      case (NegInf, _)            => NegInf
      case (_, NegInf)            => NegInf
      case (Closed(a), Closed(b)) => if order.lteqv(a, b) then x else y
      case (Open(a), Open(b))     => if order.lteqv(a, b) then x else y
      case (Open(a), Closed(b))   => if order.lteqv(a, b) then x else y
      case (Closed(a), Open(b))   => if order.lt(a, b) then x else y

    Interval(maxLower(lower, that.lower), minUpper(upper, that.upper))

  def toClosed(using domain: DiscreteDomain[A]): Option[(A, A)] =
    import Bound.*

    val lowerValue: Option[A] = lower match
      case NegInf        => domain.minValue
      case PosInf        => None
      case Closed(value) => Some(value)
      case Open(value)   => domain.next(value)

    val upperValue: Option[A] = upper match
      case PosInf        => domain.maxValue
      case NegInf        => None
      case Closed(value) => Some(value)
      case Open(value)   => domain.prev(value)

    (lowerValue, upperValue) match
      case (Some(l), Some(u)) if domain.order.lteqv(l, u) => Some((l, u))
      case _                                              => None

  def isEmpty(using domain: DiscreteDomain[A]): Boolean =
    toClosed.exists { case (l, u) => domain.order.gt(l, u) }

object Interval:
  def closed[A](lower: A, upper: A): Interval[A]     = Interval(Bound.Closed(lower), Bound.Closed(upper))
  def open[A](lower: A, upper: A): Interval[A]       = Interval(Bound.Open(lower), Bound.Open(upper))
  def closedOpen[A](lower: A, upper: A): Interval[A] = Interval(Bound.Closed(lower), Bound.Open(upper))
  def openClosed[A](lower: A, upper: A): Interval[A] = Interval(Bound.Open(lower), Bound.Closed(upper))
  def unbounded[A]: Interval[A]                      = Interval(Bound.NegInf, Bound.PosInf)
