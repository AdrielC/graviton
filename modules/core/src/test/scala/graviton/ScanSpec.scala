package graviton

import zio.*
import zio.stream.*
import zio.test.*

object ScanSpec extends ZIOSpecDefault:
  def spec = suite("ScanSpec")(
    test("running sum emits totals and final state") {
      val scan = Scan[Tuple1[Int], Int, Int](Tuple1(0))(
        { (reg, i) =>
          val s = reg.getAt[0, Int]
          val s2 = s + i
          reg.setAt[0, Int](s2)
          Chunk.single(s2)
        },
        reg => Chunk.single(reg.getAt[0, Int])
      )
      for out <- ZStream(1, 2, 3).via(scan.toPipeline).runCollect
      yield assertTrue(out == Chunk(1, 3, 6, 6))
    }
  )
