package graviton

import cats.collections.Range as IncRange
import graviton.ranges.{DiscreteDomain, Span}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*
import scala.annotation.targetName

type ByteIndex  = Long :| GreaterEqual[0]
type ByteLength = Long :| GreaterEqual[0]

final case class ByteRange private (start: ByteIndex, endExclusive: ByteIndex):
  inline def startValue: Long        = start
  inline def endExclusiveValue: Long = endExclusive
  inline def isEmpty: Boolean        = startValue >= endExclusiveValue
  inline def length: ByteLength      = (endExclusiveValue - startValue).asInstanceOf[ByteLength]
  inline def span: Span[Long]        = Span(startValue, endExclusiveValue)

  inline def toInclusive: IncRange[Long] =
    val domain       = summon[DiscreteDomain[Long]]
    val inclusiveEnd = domain.prev(endExclusiveValue).getOrElse(startValue)
    IncRange(startValue, inclusiveEnd)

object ByteRange:
  @targetName("fromLongs")
  def apply(start: Long, endExclusive: Long): ByteRange =
    from(start, endExclusive).getOrElse(throw new IllegalArgumentException(s"Invalid byte range [$start,$endExclusive)"))

  def from(start: Long, endExclusive: Long): Either[String, ByteRange] =
    for
      s <- start.refineEither[GreaterEqual[0]]
      e <- endExclusive.refineEither[GreaterEqual[0]]
      _ <- Either.cond(endExclusive >= start, (), s"End $endExclusive must be >= start $start")
    yield new ByteRange(s, e)

  def unsafe(start: Long, endExclusive: Long): ByteRange =
    new ByteRange(start.asInstanceOf[ByteIndex], endExclusive.asInstanceOf[ByteIndex])

  def unapply(range: ByteRange): Option[(Long, Long)] =
    Some((range.startValue, range.endExclusiveValue))

  extension (range: ByteRange)
    inline def startLong: Long        = range.startValue
    inline def endExclusiveLong: Long = range.endExclusiveValue
    inline def lengthValue: Long      = range.endExclusiveValue - range.startValue
