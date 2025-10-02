package graviton

import graviton.ranges.*
import graviton.ranges.RangeSetJson.given
import zio.*
import zio.json.*
import zio.test.*

object RangeSpec extends ZIOSpecDefault:
  private given DiscreteDomain[Long] = DiscreteDomain.longDomain

  def spec =
    suite("RangeSet")(
      test("union and holes identify missing spans") {
        val rs1    = RangeSet.fromSpans(List(Span(0L, 4L), Span(8L, 12L)))
        val rs2    = RangeSet.fromSpans(List(Span(4L, 8L)))
        val merged = rs1.union(rs2)
        val holes  = merged.holes(Interval.closedOpen(0L, 12L))
        assertTrue(holes.isEmpty)
      },
      test("holes surfaces uncovered segments inside window") {
        val set   = RangeSet.fromSpans(List(Span(0L, 4L), Span(10L, 12L)))
        val holes = set.holes(Interval.closedOpen(0L, 12L))
        assertTrue(holes == List(Span(4L, 10L)))
      },
      test("isComplete matches coverage boundaries") {
        val set = RangeSet.fromSpans(List(Span(0L, 6L), Span(6L, 12L)))
        assertTrue(set.isComplete(Interval.closedOpen(0L, 12L)))
      },
      test("JSON codec round trips RangeSet[Long]") {
        val set     = RangeSet.fromSpans(List(Span(0L, 4L), Span(8L, 12L)))
        val json    = set.toJson
        val decoded = json.fromJson[RangeSet[Long]]
        assertTrue(decoded == Right(set))
      },
      test("ByteRange conversions stay end-exclusive") {
        val range   = ByteRange(0L, 1024L)
        val span    = range.span
        val rebuilt = Span.toInclusiveRange(span).flatMap { inc =>
          DiscreteDomain.longDomain.next(inc.end).map(end => Span(inc.start, end))
        }
        assertTrue(rebuilt.contains(span))
      },
      test("ByteRange validation rejects negatives and inverted bounds") {
        val negativeStart = ByteRange.from(-1L, 10L)
        val inverted      = ByteRange.from(10L, 5L)
        assertTrue(negativeStart.isLeft && inverted.isLeft)
      },
    )
