package graviton

import zio.*
import zio.stream.*
import zio.test.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

object ScanSpec extends ZIOSpecDefault:
  def spec = suite("ScanSpec")(
    test("running sum emits totals and final state") {
      val scan = Scan.stateful(0) { (s: Int, i: Int) =>
        val sum = s + i
        (sum, Chunk.single(sum))
      }(s => Chunk.single(s))
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
        .stateful(0)({ (s: Int, i: Int) =>
          val sum = s + i
          (sum, Chunk.single(sum))
        })(s => Chunk.single(s))
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
      val stateful = Scan.stateful(0)({ (s: Int, i: Int) =>
        val sum = s + i
        (sum, Chunk.single(sum))
      })(_ => Chunk.empty)
      val composed = stateless.andThen(stateful)
      for out <- ZStream(1, 2).via(composed.toPipeline).runCollect
      yield assertTrue(composed.initial == Tuple1(0)) &&
        assertTrue(out == Chunk(2, 5))
    },
    test("stateful composition appends state tuples") {
      val s1 = Scan.stateful(0)({ (s: Int, i: Int) =>
        (s + i, Chunk.single(i))
      })(_ => Chunk.empty)
      val s2 = Scan.stateful(0)({ (s: Int, i: Int) =>
        (s + i, Chunk.single(s + i))
      })(s => Chunk.single(s))
      val composed = s1.andThen(s2)
      for
        out <- ZStream(1, 2).via(composed.toPipeline).runCollect
        init = composed.initial.asInstanceOf[(Int, Int)]
      yield assertTrue(init == (0, 0)) &&
        assertTrue(out == Chunk(1, 3, 3))
    },
    test("many stateless functions fuse without blowing the stack") {
      val scans = List.fill(1000)(Scan.stateless((i: Int) => i + 1))
      val composed: Scan[Int, Int] = scans.reduce[Scan[Int, Int]](_.andThen(_))
      for out <- ZStream(0).via(composed.toPipeline).runCollect
      yield assertTrue(out == Chunk(1000))
    },
    test("statelessChunk emits multiple outputs") {
      val scan = Scan.statelessChunk((i: Int) => Chunk(i, i + 1))
      for out <- ZStream(1, 2).via(scan.toPipeline).runCollect
      yield assertTrue(out == Chunk(1, 2, 2, 3))
    },
    test("zip runs two scans in parallel") {
      val s1 = Scan.stateless((i: Int) => i + 1)
      val s2 = Scan.stateless((i: Int) => i * 2)
      val zipped = s1.zip(s2)
      for out <- ZStream(1, 2).via(zipped.toPipeline).runCollect
      yield assertTrue(out == Chunk((2, 2), (3, 4)))
    },
    test("hashAndCount computes digest and length") {
      val bytes = Chunk.fromArray("abc".getBytes)
      val scan = Scan.hashAndCount(HashAlgorithm.SHA256)
      for
        out <- ZStream.fromChunk(bytes).via(scan.toPipeline).runCollect
        digest <- Hashing
          .compute(Bytes(ZStream.fromChunk(bytes)), HashAlgorithm.SHA256)
        digRef = digest.assume[MinLength[16] & MaxLength[64]]
      yield assertTrue(
        out == Chunk((Hash(digRef, HashAlgorithm.SHA256), bytes.length.toLong))
      )
    }
  )
