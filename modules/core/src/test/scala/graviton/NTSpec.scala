package graviton

import zio.*
import zio.test.*

object NTSpec extends ZIOSpecDefault:
  def spec = suite("NTSpec")(
    test("splitAB splits concatenated state precisely") {
      type A = (Int, String)
      val sa0: A = (1, "x")
      val whole  = (sa0 ++ Tuple1(2L)).asInstanceOf[Tuple.Concat[A, Tuple1[Long]]]
      val (sa,sb) = NT.splitAB[A, Tuple1[Long]](whole)
      assertTrue(sa == (1, "x")) && assertTrue(sb == Tuple1(2L))
    },
  )

