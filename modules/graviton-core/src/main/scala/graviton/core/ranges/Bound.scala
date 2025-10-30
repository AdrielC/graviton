package graviton.core.ranges

sealed trait Bound[+A] derives CanEqual
object Bound:
  case object NegInf                   extends Bound[Nothing]
  final case class Open[A](value: A)   extends Bound[A]
  final case class Closed[A](value: A) extends Bound[A]
  case object PosInf                   extends Bound[Nothing]
