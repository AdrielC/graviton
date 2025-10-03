package graviton.ranges

import graviton.ranges.Ranges.*
import scala.annotation.targetName

/** Half-open byte range [start, endExclusive). */
final case class ByteRange private (start: RangeStart, endExclusive: RangeEnd):

  require(
    endExclusive > start,
    s"invalid byte range: endExclusive ($endExclusive) must be greater than start ($start)",
  )

  inline def length: Length =
    Length.unsafe(endExclusive - start)

  inline def lengthValue: Long = length

  inline def startValue: Long = start

  inline def endExclusiveValue: Long = endExclusive

  inline def contains(index: ByteIndex): Boolean =
    start <= index && index < endExclusive

  inline def shift(offset: Length): ByteRange =
    val delta = offset
    ByteRange.unsafe(start + delta, endExclusive + delta)

object ByteRange:

  sealed trait Error extends Product with Serializable:
    def message: String

  object Error:
    final case class InvalidStart(message: String) extends Error
    final case class InvalidEnd(message: String)   extends Error
    case object EmptyRange                         extends Error:
      val message: String = "endExclusive must be greater than start"

  def make(start: Long, endExclusive: Long): Either[Error, ByteRange] =
    for
      s <- RangeStart.from(start).left.map(Error.InvalidStart.apply)
      e <- RangeEnd.from(endExclusive).left.map(Error.InvalidEnd.apply)
      _ <- Either.cond(endExclusive > start, (), Error.EmptyRange)
    yield new ByteRange(s, e)

  def unsafe(start: Long, endExclusive: Long): ByteRange =
    make(start, endExclusive).fold(err => throw IllegalArgumentException(err.message), identity)

  def fromLength(start: Long, length: Long): Either[Error, ByteRange] =
    for
      s   <- RangeStart.from(start).left.map(Error.InvalidStart.apply)
      l   <- Length.from(length).left.map(msg => Error.InvalidEnd(s"invalid length: $msg"))
      e    = s + l
      _   <- Either.cond(e > s, (), Error.EmptyRange)
      end <- RangeEnd.from(e).left.map(Error.InvalidEnd.apply)
    yield new ByteRange(s, end)

  def unapply(range: ByteRange): Some[(Long, Long)] =
    Some((range.start, range.endExclusive))

  @targetName("fromLongs")
  def apply(start: Long, endExclusive: Long): ByteRange = unsafe(start, endExclusive)
