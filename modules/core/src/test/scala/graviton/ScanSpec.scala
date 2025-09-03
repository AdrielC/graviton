package graviton

import zio.*
import zio.stream.*
import zio.test.*

object ScanSpec extends ZIOSpecDefault:
  def spec = suite("ScanSpec")(
    test("running sum emits totals and final state") {
      val scan = Scan(Tuple1(0))({ (s: Tuple1[Int], i: Int) =>
        val sum = s._1 + i
        (Tuple1(sum), Chunk.single(sum))
      })(s => Chunk.single(s._1))
      for
        out <- ZStream(1, 2, 3).via(scan.toPipeline).runCollect
      yield assertTrue(out == Chunk(1, 3, 6, 6))
    }
  )
