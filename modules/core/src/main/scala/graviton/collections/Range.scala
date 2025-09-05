package graviton.collections

import scala.math.Ordering

/** Inclusive range [start, end] supporting discrete operations. */
final case class Range[A](start: A, end: A):

  /** Subtract a range from this range. Result may be 0, 1 or 2 ranges. */
  def -(range: Range[A])(using discrete: Discrete[A], ord: Ordering[A]): Option[(Range[A], Option[Range[A]])] =
    if ord.lteq(range.start, start) then
      if ord.lt(range.end, start) then Some((this, None))
      else if ord.gteq(range.end, end) then None
      else Some((Range(discrete.succ(range.end), end), None))
    else if ord.gt(range.start, end) then Some((this, None))
    else
      val r1 = Range(start, discrete.pred(range.start))
      val r2 =
        if ord.lt(range.end, end) then Some(Range(discrete.succ(range.end), end))
        else None
      Some((r1, r2))

  def +(other: Range[A])(using ord: Ordering[A], discrete: Discrete[A]): (Range[A], Option[Range[A]]) =
    val (l, r) =
      if ord.lt(this.start, other.start) then (this, other) else (other, this)
    if ord.gteq(l.end, r.start) || discrete.adj(l.end, r.start) then (Range(l.start, ord.max(l.end, r.end)), None)
    else (Range(l.start, l.end), Some(Range(r.start, r.end)))

  def &(other: Range[A])(using ord: Ordering[A]): Option[Range[A]] =
    val s = ord.max(this.start, other.start)
    val e = ord.min(this.end, other.end)
    if ord.lteq(s, e) then Some(Range(s, e)) else None

  def contains(range: Range[A])(using ord: Ordering[A]): Boolean =
    ord.lteq(start, range.start) && ord.gteq(end, range.end)

  def overlaps(range: Range[A])(using ord: Ordering[A]): Boolean =
    contains(range.start) || contains(range.end) || range.contains(
      start
    ) || range.contains(end)

  def contains(x: A)(using ord: Ordering[A]): Boolean =
    ord.gteq(x, start) && ord.lteq(x, end)

  def toIterator(using discrete: Discrete[A], ord: Ordering[A]): Iterator[A] =
    val next: A => A =
      if ord.lt(start, end) then discrete.succ else discrete.pred
    Iterator
      .iterate(start)(next)
      .takeWhile(a => !ord.equiv(a, end))
      .++(Iterator.single(end))

  def toList(using discrete: Discrete[A], ord: Ordering[A]): List[A] =
    toIterator.toList

  def reverse: Range[A] = Range(end, start)

  def foreach(
    f: A => Unit
  )(using discrete: Discrete[A], ord: Ordering[A]): Unit =
    var i = start
    while ord.lt(i, end) do
      f(i)
      i = discrete.succ(i)
    if ord.equiv(i, end) then f(i)

  def map[B](f: A => B): Range[B] = Range(f(start), f(end))

  def foldLeft[B](s: B, f: (B, A) => B)(using discrete: Discrete[A], ord: Ordering[A]): B =
    var b = s
    foreach { a => b = f(b, a) }
    b

  def foldRight[B](s: B, f: (A, B) => B)(using discrete: Discrete[A], ord: Ordering[A]): B =
    reverse.foldLeft(s, (b: B, a: A) => f(a, b))(using discrete.inverse, ord.reverse)
