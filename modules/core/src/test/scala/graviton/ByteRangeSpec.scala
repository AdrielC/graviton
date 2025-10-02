package graviton

import graviton.Ranges.*
import zio.test.*

object ByteRangeSpec extends ZIOSpecDefault:
  def spec =
    suite("ByteRange")(
      test("make produces half-open range with expected length") {
        val range = ByteRange.make(2L, 10L)
        assertTrue(range.exists(_.length == Length.unsafe(8L)))
      },
      test("make rejects invalid bounds") {
        val negativeStart = ByteRange.make(-1L, 5L)
        val inverted      = ByteRange.make(5L, 5L)
        assertTrue(negativeStart.isLeft, inverted.swap.exists(_ == ByteRange.Error.EmptyRange))
      },
      test("fromLength computes endExclusive from length") {
        val range = ByteRange.fromLength(4L, 6L)
        assertTrue(range.contains(ByteRange(4L, 10L)))
      },
      test("contains respects start-inclusive end-exclusive semantics") {
        val range = ByteRange(0L, 4L)
        assertTrue(range.contains(ByteIndex.unsafe(0L)), !range.contains(ByteIndex.unsafe(4L)))
      },
      test("shift moves both bounds by the provided length") {
        val shifted = ByteRange(1L, 4L).shift(Length.unsafe(3L))
        assertTrue(shifted == ByteRange(4L, 7L))
      },
    )
