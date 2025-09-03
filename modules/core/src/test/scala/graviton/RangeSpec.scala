package graviton

import zio.*
import zio.test.*
import graviton.collections.*

object RangeSpec extends ZIOSpecDefault:
  def spec = suite("RangeSpec")(
    test("basic contains and iteration") {
      given Discrete[Int] = Discrete.integralDiscrete[Int]
      given Ordering[Int] = Ordering.Int
      val r = Range(1,5)
      val list = r.toList
      assertTrue(r.contains(3), list == List(1,2,3,4,5))
    }
  )
