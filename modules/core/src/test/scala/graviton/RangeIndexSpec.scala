package graviton

import graviton.Ranges.*
import zio.test.*

object RangeIndexSpec extends ZIOSpecDefault:
  def spec =
    suite("RangeIndex")(
      test("add merges ranges and exposes intervals") {
        val rangeA   = ByteRange(0L, 4L)
        val rangeB   = ByteRange(4L, 8L)
        val combined = RangeIndex.empty.add(rangeA).add(rangeB)
        assertTrue(
          combined.contains(rangeA),
          combined.contains(rangeB),
          combined.intervals == List(ByteRange(0L, 8L)),
        )
      },
      test("remove and containsPoint behave as expected") {
        val range      = ByteRange(2L, 6L)
        val index      = RangeIndex.empty.add(range)
        val after      = index.remove(range)
        val within     = ByteIndex.unsafe(4L)
        val afterPoint = ByteIndex.unsafe(4L)
        assertTrue(index.containsPoint(within), !after.contains(range), !after.containsPoint(afterPoint))
      },
      test("holes returns complement within total length") {
        val index = RangeIndex.empty
          .add(ByteRange(1L, 3L))
          .add(ByteRange(5L, 7L))
        val holes = index.holes(Length.unsafe(8L))
        assertTrue(holes == List(ByteRange(0L, 1L), ByteRange(3L, 5L), ByteRange(7L, 8L)))
      },
      test("union and intersection combine indexes") {
        val left  = RangeIndex.empty.add(ByteRange(0L, 5L))
        val right = RangeIndex.empty.add(ByteRange(3L, 8L))
        val union = left.union(right)
        val inter = left.intersect(right)
        assertTrue(
          union.intervals == List(ByteRange(0L, 8L)),
          inter.intervals == List(ByteRange(3L, 5L)),
        )
      },
      test("difference subtracts ranges") {
        val left   = RangeIndex.empty.add(ByteRange(0L, 10L))
        val right  = RangeIndex.empty.add(ByteRange(3L, 6L))
        val diff   = left.difference(right)
        val expect = List(ByteRange(0L, 3L), ByteRange(6L, 10L))
        assertTrue(diff.intervals == expect)
      },
    )
