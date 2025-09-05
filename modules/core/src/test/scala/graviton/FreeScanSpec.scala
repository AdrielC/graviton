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
    test("product *** runs scans on pair independently") {
      val a    = FreeScan.stateless1((i: Int) => i + 1)
      val b    = FreeScan.stateless1((j: Int) => j * 2)
      val both = (a *** b).compile
      for out <- ZStream((1, 2), (3, 4)).via(both.toPipeline).runCollect
      yield assertTrue(out == Chunk((2, 4), (4, 8)))
    },
    test("first lifts scan to first of pair") {
      val a  = FreeScan.stateless1((i: Int) => i + 1)
      val fs = a.first[String].compile
      for out <- ZStream((1, "a"), (2, "b")).via(fs.toPipeline).runCollect
      yield assertTrue(out == Chunk((2, "a"), (3, "b")))
    },
    test("second lifts scan to second of pair") {
      val a  = FreeScan.stateless1((i: Int) => i + 1)
      val fs = a.second[String].compile
      for out <- ZStream(("a", 1), ("b", 2)).via(fs.toPipeline).runCollect
      yield assertTrue(out == Chunk(("a", 2), ("b", 3)))
    },
    test("left applies to Left, passes Right through") {
      val a  = FreeScan.stateless1((i: Int) => i + 1)
      val fs = a.left[String].compile
      for out <- ZStream(Left(1), Right("x"), Left(2)).via(fs.toPipeline).runCollect
      yield assertTrue(out == Chunk(Left(2), Right("x"), Left(3)))
    },
    test("right applies to Right, passes Left through") {
      val a  = FreeScan.stateless1((i: Int) => i + 1)
      val fs = a.right[String].compile
      for out <- ZStream(Left("x"), Right(1), Right(2)).via(fs.toPipeline).runCollect
      yield assertTrue(out == Chunk(Left("x"), Right(2), Right(3)))
    },
    test("+++ chooses based on Either side and merges states") {
      val a    = FreeScan.stateless1((i: Int) => i + 1)
      val b    = FreeScan.stateless1((j: Int) => j * 2)
      val both = (a +++ b).compile
      for out <- ZStream(Left(1), Right(2), Left(3)).via(both.toPipeline).runCollect
      yield assertTrue(out == Chunk(Left(2), Right(4), Left(4))) && assertTrue(both.initial == EmptyTuple)
    },
    test("||| fuses outputs from Either branches") {
      val a = FreeScan.stateless1((i: Int) => i + 1)
      val b = FreeScan.stateless1((j: Int) => j * 2)
      val f = (a ||| b).compile
      for out <- ZStream(Left(1), Right(2), Left(3)).via(f.toPipeline).runCollect
      yield assertTrue(out == Chunk(2, 4, 4)) && assertTrue(f.initial == EmptyTuple)
    },
    test("stateful then stateless composes with correct State type") {
      val st: FreeScan.Aux[Int, Int, Tuple1[Int]] = FreeScan.stateful(0) { (s: Int, i: Int) =>
        val s1 = s + i
        (s1, Chunk.single(i))
      }(s => Chunk.single(s))
      val sl: FreeScan.Aux[Int, Int, EmptyTuple]  = FreeScan.stateless1((i: Int) => i * 2)
      val comp                                    = st.andThen(sl)
      val scan                                    = comp.compile
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
