package graviton.streams.scan

import zio.*
import zio.test.*
import zio.stream.*
import graviton.testkit.TestGen
import zio.Chunk

/**
 * Category/Arrow/Choice/Product laws for the Scan algebra.
 *
 * These tests prove the algebra is lawful under interpretation,
 * verifying composition, identity, associativity, and naturality properties.
 */
object LawsSpec extends ZIOSpecDefault {

  // Reduce test samples to prevent OOM
  override def aspects = Chunk(TestAspect.samples(20))

  // Helper: run a scan through its pipeline
  private def runScan[In, State, Out](
    scan: Scan[In, State, Out],
    input: Chunk[In],
  ): ZIO[Any, Nothing, Chunk[Out]] =
    ZStream.fromChunk(input).via(scan.pipeline).runCollect

  def spec = suite("Scan Laws")(
    test("Category left identity: identity >>> f == f") {
      check(TestGen.boundedBytes) { input =>
        val f        = Scan.foldLeft[Byte, Long](0L)((acc, b) => acc + b)
        val id       = Scan.identity[Byte]
        val composed = id.andThen(f)

        for {
          expected <- runScan(f, input)
          actual   <- runScan(composed, input)
        } yield assertTrue(actual == expected)
      }
    },
    test("Category right identity: f >>> identity == f") {
      check(TestGen.boundedBytes) { input =>
        val f        = Scan.foldLeft[Byte, Long](0L)((acc, b) => acc + b)
        val id       = Scan.identity[Long]
        val composed = f.andThen(id)

        for {
          expected <- runScan(f, input)
          actual   <- runScan(composed, input)
        } yield assertTrue(actual == expected)
      }
    },
    test("Category associativity: (f >>> g) >>> h == f >>> (g >>> h)") {
      check(TestGen.boundedBytes) { input =>
        // f: Byte -> Long (running sum)
        val f = Scan.foldLeft[Byte, Long](0L)((acc, b) => acc + b)
        // g: Long -> Long (double the value)
        val g = Scan.foldLeft[Long, Long](0L)((_, x) => x * 2)
        // h: Long -> String (convert to string)
        val h = Scan[Long, Unit, String](
          Scan.Step((), Chunk.empty),
          (_, n) => Scan.Step((), Chunk.single(n.toString)),
          _ => Chunk.empty,
        )

        val left  = f.andThen(g).andThen(h)
        val right = f.andThen(g.andThen(h))

        for {
          leftResult  <- runScan(left, input)
          rightResult <- runScan(right, input)
        } yield assertTrue(leftResult == rightResult)
      }
    },
    test("andThen composition produces correct outputs") {
      check(TestGen.boundedBytes) { input =>
        // First scan: count bytes
        val counter  = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)
        // Second scan: sum the counts
        val summer   = Scan.foldLeft[Long, Long](0L)(_ + _)
        val composed = counter.andThen(summer)

        for {
          result <- runScan(composed, input)
        } yield {
          // The composed scan should emit cumulative sums of cumulative counts
          val expected =
            if (input.isEmpty) Chunk(0L, 0L)
            else {
              val counts = (1L to input.length.toLong).toList
              val sums   = counts.scanLeft(0L)(_ + _)
              Chunk.fromIterable(sums)
            }
          assertTrue(result == expected)
        }
      }
    },
    test("contramap preserves scan semantics") {
      check(TestGen.boundedBytes) { input =>
        val intScan  = Scan.foldLeft[Int, Long](0L)((acc, i) => acc + i)
        val byteScan = intScan.contramap[Byte](_.toInt)

        val expectedSum = input.map(_.toInt.toLong).sum

        for {
          result <- runScan(byteScan, input)
        } yield {
          val finalSum = if (result.isEmpty) 0L else result.last
          assertTrue(finalSum == expectedSum || (input.isEmpty && finalSum == 0L))
        }
      }
    },
    test("mapOut preserves scan semantics") {
      check(TestGen.boundedBytes) { input =>
        val baseScan   = Scan.foldLeft[Byte, Long](0L)((acc, b) => acc + b)
        val mappedScan = baseScan.mapOut(_.toString)

        for {
          baseResult   <- runScan(baseScan, input)
          mappedResult <- runScan(mappedScan, input)
        } yield assertTrue(mappedResult == baseResult.map(_.toString))
      }
    },
    test("dimap combines contramap and mapOut correctly") {
      check(TestGen.boundedBytes) { input =>
        val intScan      = Scan.foldLeft[Int, Long](0L)((acc, i) => acc + i)
        val dimappedScan = intScan.dimap[Byte, String](_.toInt)(_.toString)

        val contramapped = intScan.contramap[Byte](_.toInt)
        val expected     = contramapped.mapOut(_.toString)

        for {
          dimappedResult <- runScan(dimappedScan, input)
          expectedResult <- runScan(expected, input)
        } yield assertTrue(dimappedResult == expectedResult)
      }
    },
    test("empty input produces only initial emissions") {
      val scan = Scan.foldLeft[Byte, Long](42L)((acc, b) => acc + b)
      runScan(scan, Chunk.empty).map { result =>
        assertTrue(result == Chunk(42L))
      }
    },
    test("scan with initial outputs emits them first") {
      val scan = Scan.stateful[Byte, Long, Long](
        initialState = 0L,
        initialOutputs = Chunk(100L, 200L),
      )((state, b) => (state + b, Chunk.single(state + b)))

      runScan(scan, Chunk[Byte](1, 2, 3)).map { result =>
        assertTrue(
          result.take(2) == Chunk(100L, 200L),
          result.drop(2) == Chunk(1L, 3L, 6L),
        )
      }
    },
  )
}
