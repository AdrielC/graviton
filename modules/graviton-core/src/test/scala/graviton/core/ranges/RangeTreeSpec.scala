package graviton.core.ranges

import zio.test.*

object RangeTreeSpec extends ZIOSpecDefault:

  private def span(start: Long, end: Long): Span[Long] =
    Span.make(start, end).fold(err => throw new IllegalArgumentException(err), identity)

  override def spec: Spec[TestEnvironment, Any] =
    suite("RangeTree")(
      test("insert normalizes overlapping spans") {
        val tree = RangeTree
          .empty[Long]
          .insert(span(0, 5))
          .insert(span(4, 10))
          .insert(span(20, 25))

        val expected = Vector(span(0, 10), span(20, 25))
        assertTrue(tree.spans == expected) && assertTrue(tree.size == 2)
      },
      test("contains performs logarithmic membership lookups") {
        val tree = RangeTree
          .empty[Long]
          .insert(span(0, 5))
          .insert(span(10, 12))

        assertTrue(tree.contains(3)) &&
        assertTrue(!tree.contains(7)) &&
        assertTrue(tree.contains(11))
      },
      test("covered length aggregates span widths") {
        val tree = RangeTree.empty[Long].insert(span(0, 5)).insert(span(10, 12))
        assertTrue(tree.coveredLength == 9L)
      },
      test("missingWithin reports normalized gaps inside a window") {
        val tree     = RangeTree.empty[Long].insert(span(0, 5)).insert(span(10, 12))
        val window   = span(0, 15)
        val gaps     = tree.missingWithin(window)
        val expected = Vector(span(6, 9), span(13, 15))
        assertTrue(gaps.spans == expected)
      },
      test("nextGap skips covered areas and finds the next missing region") {
        val tree = RangeTree
          .empty[Long]
          .insert(span(0, 5))
          .insert(span(10, 15))

        val gapFromZero  = tree.nextGap(0)
        val gapFromSeven = tree.nextGap(7)
        assertTrue(gapFromZero.contains(span(6, 9))) &&
        assertTrue(gapFromSeven.contains(span(7, 9)))
      },
      test("toRangeSet and fromRangeSet round-trip") {
        val original = RangeSet.fromSpans(Vector(span(0, 5), span(10, 12)))
        val rebuilt  = RangeTree.fromRangeSet(original).toRangeSet
        assertTrue(rebuilt.spans == original.spans)
      },
    )
