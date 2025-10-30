package graviton.core.ranges

final case class Span[A] private (start: A, end: A)(using Ordering[A]):
  def length(using Numeric[A]): A = summon[Numeric[A]].minus(end, start)

object Span:
  def make[A: Ordering](start: A, end: A): Either[String, Span[A]] =
    val ord = summon[Ordering[A]]
    if ord.lteq(start, end) then Right(new Span(start, end))
    else Left("Span start must be <= end")
