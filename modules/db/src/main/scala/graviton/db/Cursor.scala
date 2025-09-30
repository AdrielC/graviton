package graviton.db

import zio.*
import zio.prelude.*
import zio.prelude.classic.Monoid

opaque type Max[A] <: A = A
object Max:
  def apply[A](a: A): Max[A] = a
  def unapply[A](a: Max[A]): Option[A] = Some(a)

  given [A](using PartialOrd[A], Identity[A]): Monoid[Max[A]] with
    def identity: Max[A] = Max(Identity[A].identity)
    def combine(a: => Max[A], b: => Max[A]): Max[A] = a.maximum(b)

  extension [A](a: Max[A])
    def value: A = a
    def maximum(other: Max[A])(using PartialOrd[A]): Max[A] =
      if a > other then a else other

/** Tracks pagination state for blob-store listings. */
final case class Cursor(
  queryId: Option[java.util.UUID],
  offset: Long,
  total: Option[Max[Long]],
  pageSize: Long,
):
  def isLast: Boolean = total.exists(_ <= offset) || pageSize == 0

  def next(lastPageSize: Long): Cursor = Cursor(
    queryId,
    offset + lastPageSize,
    total.filter(_ > offset + lastPageSize),
    pageSize,
  )

  def combine(other: Cursor): Cursor =
    if queryId == other.queryId then other.next(other.offset) else this

  def withTotal(newTotal: Max[Long]): Cursor =
    copy(total = total.map(_.maximum(newTotal)).orElse(Some(newTotal)))

  def withQueryId(newQueryId: Option[java.util.UUID]): Cursor =
    copy(queryId = newQueryId)

object Cursor:
  val emptyQueryId: java.util.UUID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000000")

  given Monoid[Cursor] with
    def identity: Cursor = Cursor(None, 0L, None, 0L)
    def combine(a: => Cursor, b: => Cursor): Cursor =
      if a.queryId == b.queryId |
          b.queryId.contains(emptyQueryId) |
          a.queryId.contains(emptyQueryId)
      then
        a.next(b.offset)
          .withQueryId(
            Seq(a.queryId, b.queryId).flatten
              .filter(_ != emptyQueryId)
              .headOption
          )
      else b.total.fold(a)(a.withTotal).next(b.offset)

  final case class Patch(offset: Long, total: Option[Max[Long]])

  val differ: Differ[Cursor, Patch] = new Differ[Cursor, Patch]:
    def combine(first: Patch, second: Patch): Patch =
      Patch(
        first.offset + second.offset,
        first.total
          .as(first)
          .zipWith(second.total.as(second)) { (a, b) =>
            (a.total
              .zipWith(b.total)(_ min _))
              .flatMap { t =>
                Some(t).filter(_ > a.offset + second.offset)
              }
              .orElse(first.total.orElse(second.total))
          }
          .flatten,
      )

    def diff(oldValue: Cursor, newValue: Cursor): Patch =
      Patch(newValue.offset - oldValue.offset, newValue.total.filter(_ > newValue.offset))

    def empty: Patch = Patch(0L, None)

    def patch(patch: Patch)(oldValue: Cursor): Cursor =
      oldValue
        .next(patch.offset)
        .withTotal(patch.total.getOrElse(oldValue.total.getOrElse(Max(0L))))

  object ref:
    val cursorRef: FiberRef[Cursor] = Unsafe.unsafe { implicit u =>
      FiberRef.unsafe.makePatch(
        initial,
        Cursor.differ,
        Patch(0L, None),
        (a, b) => a.combine(b),
      )
    }

  val initial: Cursor = Cursor(None, 0L, None, 100L)
