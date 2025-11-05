package graviton.core.ranges

final case class Interval[A](lower: Bound[A], upper: Bound[A])

object Interval:
  def closed[A](start: A, end: A): Interval[A] = Interval(Bound.Closed(start), Bound.Closed(end))
  def open[A](start: A, end: A): Interval[A]   = Interval(Bound.Open(start), Bound.Open(end))
