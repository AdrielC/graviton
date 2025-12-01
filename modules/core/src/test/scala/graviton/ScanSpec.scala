package graviton

import zio.*
import zio.stream.*
import zio.test.*
import graviton.core.model.Block
import graviton.core.model.Size
import Size.given

import zio.prelude.NonEmptySortedMap

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
      val add1     = Scan.stateless1((i: Int) => i + 1)
      val double   = Scan.stateless1((i: Int) => i * 2)
      val composed = add1.andThen(double)
      val id       = Scan.identity[Int]
      for
        out1 <- ZStream(1, 2).via(composed.toPipeline).runCollect
        out2 <- ZStream(1, 2, 3).via(id.toPipeline).runCollect
      yield assertTrue(composed.initial.asInstanceOf[Unit] == ()) &&
        assertTrue(out1 == Chunk(4, 6)) &&
        assertTrue(out2 == Chunk(1, 2, 3))
    },
    test("map transforms output values") {
      val scan = Scan
        .stateful(0) { (s: Int, i: Int) =>
          val sum = s + i
          (sum, Chunk.single(sum))
        }(s => Chunk.single(s))
        .map(_ * 2)
      for out <- ZStream(1, 2, 3).via(scan.toPipeline).runCollect
      yield assertTrue(out == Chunk(2, 6, 12, 12))
    },
    test("contramap preprocesses input") {
      val scan = Scan.stateless1((i: Int) => i + 1).contramap[String](_.toInt)
      for out <- ZStream("1", "2").via(scan.toPipeline).runCollect
      yield assertTrue(out == Chunk(2, 3))
    },
    test("dimap handles both input and output") {
      val scan = Scan
        .stateless1((i: Int) => i + 1)
        .dimap[String, String](_.toInt)(_.toString)
      for out <- ZStream("1", "2").via(scan.toPipeline).runCollect
      yield assertTrue(out == Chunk("2", "3"))
    },
    test("stateful composed after stateless keeps only stateful state") {
      inline def stateless = Scan.stateless1((i: Int) => i + 1)
      inline def stateful  = Scan.stateful(0) { (s: Int, i: Int) =>
        val sum = s + i
        (sum, Chunk.single(sum))
      }(_ => Chunk.empty)
      inline def composed  = stateless.andThen(stateful)
      for out <- ZStream(1, 2).via(composed.toPipeline).runCollect
      yield assertTrue(composed.initial == Tuple1(0)) &&
        assertTrue(out == Chunk(2, 5))
    },
    test("stateful composition appends state tuples") {
      val s1       = Scan.stateful(0)((s: Int, i: Int) => (s + i, Chunk.single(i)))(_ => Chunk.empty)
      val s2       = Scan.stateful(0)((s: Int, i: Int) => (s + i, Chunk.single(s + i)))(s => Chunk.single(s))
      val composed = s1.andThen(s2)
      for
        out <- ZStream(1, 2).via(composed.toPipeline).runCollect
        init = composed.initial
      yield assertTrue(init == (0, 0)) &&
        assertTrue(out == Chunk(1, 3, 3))
    },
    test("many stateless functions fuse without blowing the stack") {
      val scans                    = List.fill(1000)(Scan.stateless1((i: Int) => i + 1))
      val composed: Scan[Int, Int] = scans.reduce[Scan[Int, Int]](_.andThen(_))
      for out <- ZStream(0).via(composed.toPipeline).runCollect
      yield assertTrue(out == Chunk(1000))
    },
    test("stateless emits multiple outputs") {
      val scan = Scan.stateless((i: Int) => Chunk(i, i + 1))
      for out <- ZStream(1, 2).via(scan.toPipeline).runCollect
      yield assertTrue(out == Chunk(1, 2, 2, 3))
    },
    test("zip runs two scans in parallel") {
      inline def s1     = Scan.stateless1((i: Int) => i + 1)
      inline def s2     = Scan.stateless1((i: Int) => i * 2)
      inline def zipped = s1.zip(s2)
      for out <- ZStream(1, 2).via(zipped.toPipeline).runCollect
      yield assertTrue(out == Chunk((2, 2), (3, 4)))
    },
    test("hashAndCount computes digest and length") {
      val bytes = Block.applyUnsafe(Chunk.fromArray("abc".getBytes))
      val scan  = Scan.hashAndCount(HashAlgorithm.SHA256)
      for
        out    <- ZStream.succeed(bytes).via(scan.toPipeline).runCollect
        digest <- Hashing.ref.locally(NonEmptySortedMap(HashAlgorithm.SHA256 -> None))(Hashing.compute(Bytes(bytes)))
        digRef  = digest.bytes.get(HashAlgorithm.SHA256).get
      yield assertTrue(
        out == Chunk((Hash.SingleHash(HashAlgorithm.SHA256, digRef), bytes.length.toLong))
      )
    },
    test("toChannel yields Take-based channel") {
      val scan     = Scan.count
      val pipeline = ZPipeline.fromChannel(
        scan.toChannel.mapOut(
          _.fold(Chunk.empty[Long], cause => throw cause.squash, chunk => chunk)
        )
      )
      for out <- ZStream(1, 2, 3).via(pipeline).runCollect
      yield assertTrue(out == Chunk(3L))
    },
    test("runAll processes iterable without ZIO") {
      val (st, out) = Scan.count.runAll(List(Block.applyUnsafe(Chunk.fromArray("abc".getBytes))))
      assertTrue(st.head.asInstanceOf[Long] == 3L) && assertTrue(out == Chunk(3L))
    },
    test("chunkBy splits input into fixed-size chunks") {
      val scan = Scan.fixedSize[Size.type](2)
      val bytes = NonEmptyChunk.fromChunk(Chunk.fromArray("abc".getBytes)).get
      val (_, out) = scan.runAll(Chunk(bytes))
      assertTrue(out == Chunk(Block.applyUnsafe(bytes)))
    },
    test("toSink collects all outputs") {
      val scan = Scan.stateless1((i: Int) => i * 2)
      for out <- ZStream(1, 2, 3).run(scan.toSink)
      yield assertTrue(out == Chunk(2, 4, 6))
    },
    test("runZPure yields final state and outputs"):
      val scan      = Scan.stateful(0) { (s: Int, i: Int) =>
        val sum = s + i
        (sum, Chunk.single(sum))
      }(s => Chunk.single(s))
      val (st, out) = scan.runZPure(List(1, 2, 3)).run(0)
      assertTrue(out == Chunk(1, 3, 6, 6)) && assertTrue(st.head.asInstanceOf[Int] == 6),
  )
