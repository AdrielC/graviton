package graviton

import zio.*
import zio.stream.*
import zio.test.*

object ScanSpec extends ZIOSpecDefault:
  def spec = suite("ScanSpec")(
    test("running sum emits totals and final state") {
      val scan = Scan.stateful(Tuple1(0))({ (s: Tuple1[Int], i: Int) =>
        val sum = s._1 + i
        (Tuple1(sum), Chunk.single(sum))
      })(s => Chunk.single(s._1))
      for out <- ZStream(1, 2, 3).via(scan.toPipeline).runCollect
      yield assertTrue(out == Chunk(1, 3, 6, 6))
    },
    test("stateless composition with identity is a no-op on state") {
      val add1 = Scan.stateless((i: Int) => i + 1)
      val double = Scan.stateless((i: Int) => i * 2)
      val composed = add1.andThen(double)
      val id = Scan.identity[Int]
      for
        out1 <- ZStream(1, 2).via(composed.toPipeline).runCollect
        out2 <- ZStream(1, 2, 3).via(id.toPipeline).runCollect
      yield assertTrue(composed.initial == EmptyTuple) &&
        assertTrue(out1 == Chunk(4, 6)) &&
        assertTrue(out2 == Chunk(1, 2, 3))
    }
  )
