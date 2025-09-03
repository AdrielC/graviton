package graviton

import zio.*
import zio.stream.*
import zio.test.*

object ScanSpec extends ZIOSpecDefault:
  def spec = suite("ScanSpec")(
    test("running sum emits totals and final state") {
      val scan = Scan[Int, Int, Int](0)({ (s, i) =>
        val s2 = s + i
        (s2, Chunk.single(s2))
      }, s => Chunk.single(s))
      for
        out <- ZStream(1,2,3).via(scan.toPipeline).runCollect
      yield assertTrue(out == Chunk(1,3,6,6))
    }
  )
