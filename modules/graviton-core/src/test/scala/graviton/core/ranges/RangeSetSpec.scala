package graviton.core.ranges

import zio.test.*

object RangeSetSpec extends ZIOSpecDefault:

  private def span(start: Long, end: Long): Span[Long] =
    Span.make(start, end).fold(err => throw new IllegalArgumentException(err), identity)

  override def spec: Spec[TestEnvironment, Any] =
    suite("RangeSet")(
      test("Span.make rejects inverted bounds") {
        assertTrue(Span.make(10L, 5L).isLeft)
      },
      test("add merges overlapping spans") {
        val base   = RangeSet.single(span(0, 5))
        val merged = base.add(span(3, 10))
        assertTrue(merged.spans == Vector(span(0, 10)))
      },
      test("add merges adjacent spans") {
        val base   = RangeSet.single(span(0, 4))
        val merged = base.add(span(5, 9))
        assertTrue(merged.spans == Vector(span(0, 9)))
      },
      test("union keeps normalized order for disjoint ranges") {
        val left  = RangeSet.single(span(10, 12))
        val right = RangeSet.single(span(0, 5))
        val union = left.union(right)
        assertTrue(union.spans == Vector(span(0, 5), span(10, 12)))
      },
      test("union merges adjacent spans across sets") {
        val left   = RangeSet.single(span(0, 4))
        val right  = RangeSet.single(span(5, 9))
        val result = left.union(right)
        assertTrue(result.spans == Vector(span(0, 9)))
      },
      test("intersect finds overlapping window") {
        val left    = RangeSet.single(span(0, 10))
        val right   = RangeSet.single(span(5, 15))
        val overlap = left.intersect(right)
        assertTrue(overlap.spans == Vector(span(5, 10)))
      },
      test("difference removes overlapping middle segment") {
        val target   = RangeSet.single(span(0, 10))
        val removal  = RangeSet.single(span(3, 6))
        val result   = target.difference(removal)
        val expected = Vector(span(0, 2), span(7, 10))
        assertTrue(result.spans == expected)
      },
      test("difference eliminating superset yields empty set") {
        val target  = RangeSet.single(span(0, 10))
        val removal = RangeSet.single(span(0, 10))
        val result  = target.difference(removal)
        assertTrue(result.isEmpty)
      },
      test("complement identifies uncovered segments inside universe") {
        val universe = span(0, 20)
        val covered  = RangeSet.fromSpans(Vector(span(0, 4), span(10, 12)))
        val missing  = covered.complement(universe)
        assertTrue(missing.spans == Vector(span(5, 9), span(13, 20)))
      },
      test("contains respects coverage and gaps") {
        val set = RangeSet.fromSpans(Vector(span(0, 4), span(10, 12)))
        assertTrue(set.contains(2)) &&
        assertTrue(!set.contains(5)) &&
        assertTrue(set.contains(11))
      },
      test("replication gap example matches documentation scenario") {
        val expected = RangeSet.single(span(0, 10000))
        val replica1 = RangeSet.single(span(0, 5000))
        val replica2 = RangeSet.single(span(6000, 10000))
        val covered  = replica1.union(replica2)
        val gaps     = expected.difference(covered)
        assertTrue(gaps.spans == Vector(span(5001, 5999)))
      },
    )
