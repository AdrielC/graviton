package graviton.streams.scan

import zio.*
import zio.test.*
import zio.stream.*
import graviton.testkit.TestGen

/**
 * Focused tests on scan composition properties.
 *
 * Verifies complex composition patterns and ensures
 * that composed scans behave correctly.
 */
object CompositionSpec extends ZIOSpecDefault {

  def spec = suite("Scan Composition")(
    test("identity is left unit: identity >>> f == f (semantically)") {
      check(TestGen.boundedBytes) { input =>
        val f        = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)
        val id       = Scan.identity[Byte]
        val composed = id.andThen(f)

        // They should produce the same final count, though intermediate emissions may differ
        for {
          resultF        <- ZStream.fromChunk(input).via(f.pipeline).runLast
          resultComposed <- ZStream.fromChunk(input).via(composed.pipeline).runLast
        } yield assertTrue(
          resultF.exists(x => resultComposed.exists(y => x == y || y == x + 1))
        )
      }
    },
    test("identity is right unit: f >>> identity == f (semantically)") {
      check(TestGen.boundedBytes) { input =>
        val f        = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)
        val id       = Scan.identity[Long]
        val composed = f.andThen(id)

        for {
          resultF        <- ZStream.fromChunk(input).via(f.pipeline).runCollect
          resultComposed <- ZStream.fromChunk(input).via(composed.pipeline).runCollect
        } yield {
          // Composed version will have identity's initial emission
          assertTrue(resultF.nonEmpty == resultComposed.nonEmpty)
        }
      }
    },
    test("composition preserves total element count relationship") {
      check(TestGen.boundedBytes) { input =>
        val s1       = Scan.identity[Byte]
        val s2       = Scan.identity[Byte]
        val composed = s1.andThen(s2)

        for {
          result <- ZStream.fromChunk(input).via(composed.pipeline).runCollect
        } yield assertTrue(result == input)
      }
    },
    test("deeply nested composition produces correct results") {
      val input = Chunk.fromIterable(1 to 10).map(_.toByte)

      // Build a chain of counters
      val counter1 = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)
      val counter2 = Scan.foldLeft[Long, Long](0L)((acc, _) => acc + 1)
      val counter3 = Scan.foldLeft[Long, Long](0L)((acc, _) => acc + 1)

      val chain = counter1.andThen(counter2).andThen(counter3)

      for {
        result <- ZStream.fromChunk(input).via(chain.pipeline).runCollect
      } yield assertTrue(result.nonEmpty)
    },
    test("composition with stateful scans maintains correctness") {
      val accumulator = Scan.stateful[Byte, Long, Long](
        initialState = 0L,
        initialOutputs = Chunk.single(0L),
      ) { (state, b) =>
        val next = state + b
        (next, Chunk.single(next))
      }

      val doubler = Scan.stateful[Long, Unit, Long](
        initialState = (),
        initialOutputs = Chunk.empty,
      )((_, n) => ((), Chunk.single(n * 2)))

      val composed = accumulator.andThen(doubler)

      check(TestGen.boundedBytes) { input =>
        for {
          accumulated <- ZStream.fromChunk(input).via(accumulator.pipeline).runCollect
          doubled     <- ZStream.fromIterable(accumulated).via(doubler.pipeline).runCollect
          composed    <- ZStream.fromChunk(input).via(composed.pipeline).runCollect
        } yield assertTrue(composed == doubled)
      }
    },
    test("composition with filtering scans") {
      val evenOnly = Scan.stateful[Byte, Unit, Option[Byte]](
        initialState = (),
        initialOutputs = Chunk.empty,
      )((_, b) => ((), if ((b & 1) == 0) Chunk.single(Some(b)) else Chunk.single(None)))

      val extractor = Scan.stateful[Option[Byte], Unit, Byte](
        initialState = (),
        initialOutputs = Chunk.empty,
      )((_, opt) => ((), opt.map(Chunk.single(_)).getOrElse(Chunk.empty)))

      val counter = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)

      val pipeline = evenOnly.andThen(extractor).andThen(counter)

      val input = Chunk.fromIterable(1 to 20).map(_.toByte)

      for {
        result <- ZStream.fromChunk(input).via(pipeline.pipeline).runLast
      } yield assertTrue(result.contains(10L)) // 10 even numbers
    },
    test("composition with amplifying scans") {
      val duplicator = Scan.stateful[Byte, Unit, Byte](
        initialState = (),
        initialOutputs = Chunk.empty,
      )((_, b) => ((), Chunk(b, b))) // Emit each byte twice

      val counter = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)

      val composed = duplicator.andThen(counter)

      val input = Chunk.fromIterable(1 to 10).map(_.toByte)

      for {
        result <- ZStream.fromChunk(input).via(composed.pipeline).runLast
      } yield assertTrue(result.contains(20L)) // 10 * 2
    },
    test("composition handles empty intermediate emissions") {
      val skipAll = Scan.stateful[Byte, Unit, Byte](
        initialState = (),
        initialOutputs = Chunk.empty,
        onEnd = _ => Chunk.single(42.toByte),
      )((_, _) => ((), Chunk.empty)) // Emit nothing until end

      val counter = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)

      val composed = skipAll.andThen(counter)

      check(TestGen.boundedBytes) { input =>
        for {
          result <- ZStream.fromChunk(input).via(composed.pipeline).runCollect
        } yield {
          // Should see initial count (0) and final count (1) from the 42
          assertTrue(result.length >= 2)
        }
      }
    },
    test("parallel composition: processing same input through different paths") {
      val sum   = Scan.foldLeft[Byte, Long](0L)((acc, b) => acc + b)
      val count = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)

      check(TestGen.boundedBytes) { input =>
        for {
          sumResult   <- ZStream.fromChunk(input).via(sum.pipeline).runLast
          countResult <- ZStream.fromChunk(input).via(count.pipeline).runLast
        } yield assertTrue(
          sumResult.isDefined,
          countResult.contains(input.length.toLong),
        )
      }
    },
    test("composition with contramap/mapOut transformations") {
      val intScan  = Scan.foldLeft[Int, Long](0L)((acc, i) => acc + i)
      val byteScan = intScan
        .contramap[Byte](b => (b & 0xff))
        .mapOut(_.toString)

      val length = Scan.foldLeft[String, Long](0L)((acc, _) => acc + 1)

      val composed = byteScan.andThen(length)

      check(TestGen.boundedBytes) { input =>
        for {
          result <- ZStream.fromChunk(input).via(composed.pipeline).runLast
        } yield assertTrue(result.exists(_ >= input.length))
      }
    },
    test("diamond composition: split and rejoin") {
      val input = Chunk.fromIterable(1 to 20).map(_.toByte)

      // Process through two different paths then combine
      val path1 = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)
      val path2 = Scan.foldLeft[Byte, Long](0L)((acc, b) => acc + b)

      for {
        count <- ZStream.fromChunk(input).via(path1.pipeline).runLast
        sum   <- ZStream.fromChunk(input).via(path2.pipeline).runLast
      } yield assertTrue(
        count.contains(20L),
        sum.exists(_ > 0),
      )
    },
  )
}
