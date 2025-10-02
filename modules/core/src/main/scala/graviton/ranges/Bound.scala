package graviton.ranges

/** Interval bound markers supporting open/closed and infinite endpoints. */
sealed trait Bound[+A]
object Bound:
  case object NegInf                   extends Bound[Nothing]
  final case class Closed[A](value: A) extends Bound[A]
  final case class Open[A](value: A)   extends Bound[A]
  case object PosInf                   extends Bound[Nothing]
