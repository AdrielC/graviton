package graviton

import zio.*
import zio.stream.*
import zio.test.*

object FreeScanSpec extends ZIOSpecDefault:
  def spec = suite("FreeScanSpec")(
    test("identity compiles and preserves input") {
      val fs   = FreeScan.identity[Int]
      val scan = fs.compile
      for out <- ZStream(1, 2, 3).via(scan.toPipeline).runCollect
      yield assertTrue(out == Chunk(1, 2, 3)) && assertTrue(scan.initial == EmptyTuple)
    },
    test("stateful then stateless composes with correct State type") {
      val st: FreeScan.Aux[Int, Int, Tuple1[Int]] = FreeScan.stateful(0) { (s: Int, i: Int) =>
        val s1 = s + i
        (s1, Chunk.single(i))
      }(s => Chunk.single(s))
      val sl: FreeScan.Aux[Int, Int, EmptyTuple] = FreeScan.stateless1((i: Int) => i * 2)
      val comp                                   = st.andThen(sl)
      val scan                                   = comp.compile
      for out <- ZStream(1, 2).via(scan.toPipeline).runCollect
      yield assertTrue(out == Chunk(2, 4, 6)) && assertTrue(scan.initial == Tuple1(0))
    },
    test("zip merges states and zips outputs") {
      val a   = FreeScan.stateless1((i: Int) => i + 1)
      val b   = FreeScan.stateless1((i: Int) => i * 2)
      val zip = a.zip(b).compile
      for out <- ZStream(1, 2).via(zip.toPipeline).runCollect
      yield assertTrue(out == Chunk((2, 2), (3, 4))) && assertTrue(zip.initial == EmptyTuple)
    },
    test("dimap contramap map chain compiles") {
      val fs   = FreeScan.stateless1((i: Int) => i + 1).dimap[String, String](_.toInt)(_.toString)
      val scan = fs.compile
      for out <- ZStream("1", "2").via(scan.toPipeline).runCollect
      yield assertTrue(out == Chunk("2", "3"))
    },
  )

