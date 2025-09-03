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
    },
    test("map transforms output values") {
      val scan = Scan
        .stateful(Tuple1(0))({ (s: Tuple1[Int], i: Int) =>
          val sum = s._1 + i
          (Tuple1(sum), Chunk.single(sum))
        })(s => Chunk.single(s._1))
        .map(_ * 2)
      for out <- ZStream(1, 2, 3).via(scan.toPipeline).runCollect
      yield assertTrue(out == Chunk(2, 6, 12, 12))
    },
    test("contramap preprocesses input") {
      val scan = Scan.stateless((i: Int) => i + 1).contramap[String](_.toInt)
      for out <- ZStream("1", "2").via(scan.toPipeline).runCollect
      yield assertTrue(out == Chunk(2, 3))
    },
    test("dimap handles both input and output") {
      val scan = Scan
        .stateless((i: Int) => i + 1)
        .dimap[String, String](_.toInt)(_.toString)
      for out <- ZStream("1", "2").via(scan.toPipeline).runCollect
      yield assertTrue(out == Chunk("2", "3"))
    },
    test("stateful composed after stateless keeps only stateful state") {
      val stateless = Scan.stateless((i: Int) => i + 1)
      val stateful = Scan.stateful(Tuple1(0))({ (s: Tuple1[Int], i: Int) =>
        val sum = s._1 + i
        (Tuple1(sum), Chunk.single(sum))
      })(_ => Chunk.empty)
      val composed = stateless.andThen(stateful)
      for out <- ZStream(1, 2).via(composed.toPipeline).runCollect
      yield assertTrue(composed.initial == Tuple1(0)) &&
        assertTrue(out == Chunk(2, 5))
    },
    test("stateful composition appends state tuples") {
      val s1 = Scan.stateful(Tuple1(0))({ (s: Tuple1[Int], i: Int) =>
        (Tuple1(s._1 + i), Chunk.single(i))
      })(_ => Chunk.empty)
      val s2 = Scan.stateful(Tuple1(0))({ (s: Tuple1[Int], i: Int) =>
        (Tuple1(s._1 + i), Chunk.single(s._1 + i))
      })(s => Chunk.single(s._1))
      val composed = s1.andThen(s2)
      for
        out <- ZStream(1, 2).via(composed.toPipeline).runCollect
        init = composed.initial.asInstanceOf[(Int, Int)]
      yield assertTrue(init == (0, 0)) &&
        assertTrue(out == Chunk(1, 3, 3))
    }
  )
