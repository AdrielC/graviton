package graviton.core.ranges

final case class Span[A] private (start: A, end: A)(using Ordering[A]):

  def startInclusive: A = start
  def endInclusive: A   = end

  def length(using Numeric[A]): A =
    val numeric = summon[Numeric[A]]
    numeric.plus(numeric.minus(end, start), numeric.fromInt(1))

  def contains(value: A): Boolean =
    val ord = summon[Ordering[A]]
    ord.lteq(start, value) && ord.lteq(value, end)

  def overlaps(that: Span[A]): Boolean =
    val ord = summon[Ordering[A]]
    !(ord.lt(this.end, that.start) || ord.lt(that.end, this.start))

  def touches(that: Span[A])(using DiscreteDomain[A]): Boolean =
    val ord           = summon[Ordering[A]]
    val domain        = summon[DiscreteDomain[A]]
    val leftAdjacent  =
      ord.lt(this.end, that.start) && ord.equiv(domain.next(this.end), that.start)
    val rightAdjacent =
      ord.lt(that.end, this.start) && ord.equiv(domain.next(that.end), this.start)
    leftAdjacent || rightAdjacent

  def canMerge(that: Span[A])(using DiscreteDomain[A]): Boolean = overlaps(that) || touches(that)

  def merge(that: Span[A])(using DiscreteDomain[A]): Span[A] =
    require(canMerge(that), "cannot merge non-touching spans")
    val ord = summon[Ordering[A]]
    val min = if ord.lteq(this.start, that.start) then this.start else that.start
    val max = if ord.gteq(this.end, that.end) then this.end else that.end
    Span.unsafe(min, max)

  def intersection(that: Span[A]): Option[Span[A]] =
    if overlaps(that) then
      val ord   = summon[Ordering[A]]
      val lower = if ord.lteq(this.start, that.start) then that.start else this.start
      val upper = if ord.lteq(this.end, that.end) then this.end else that.end
      Some(Span.unsafe(lower, upper))
    else None

  def subtract(that: Span[A])(using DiscreteDomain[A]): Vector[Span[A]] =
    val ord = summon[Ordering[A]]
    if !overlaps(that) then Vector(this)
    else
      val domain = summon[DiscreteDomain[A]]
      val before =
        if ord.lt(this.start, that.start) then
          val candidateEnd = domain.previous(that.start)
          Option.when(ord.lteq(this.start, candidateEnd))(Span.unsafe(this.start, candidateEnd))
        else None
      val after  =
        if ord.lt(that.end, this.end) then
          val candidateStart = domain.next(that.end)
          Option.when(ord.lteq(candidateStart, this.end))(Span.unsafe(candidateStart, this.end))
        else None
      Vector(before, after).flatten

object Span:

  def make[A: Ordering](start: A, end: A): Either[String, Span[A]] =
    val ord = summon[Ordering[A]]
    if ord.lteq(start, end) then Right(new Span(start, end))
    else Left("Span start must be <= end")

  def unsafe[A: Ordering](start: A, end: A): Span[A] = new Span(start, end)

  given [A: Ordering]: Ordering[Span[A]] = Ordering.by(span => (span.startInclusive, span.endInclusive))
