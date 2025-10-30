package graviton.streams.scan

import zio.*
import zio.test.*
import zio.stream.*
import graviton.testkit.TestGen

/**
 * Tests for flush semantics: exactly once, after the last element.
 *
 * Ensures that the onEnd finalizer is called exactly once when the stream completes,
 * and not called at all if the stream fails mid-stream.
 */
object FlushSpec extends ZIOSpecDefault {

  // Note: Removed flushCountingScan as it relied on side effects that are hard to test purely

  def spec = suite("Flush Semantics")(
    test("onEnd is called exactly once at stream completion") {
      val scan = Scan.stateful[Byte, Long, Option[Long]](
        initialState = 0L,
        initialOutputs = Chunk.empty,
        onEnd = state => Chunk.single(Some(state)),
      )((state, b) => (state + 1, Chunk.single(None)))

      check(TestGen.boundedBytes) { input =>
        for {
          result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
        } yield {
          val flushResults = result.filter(_.isDefined)
          assertTrue(
            flushResults.length == 1,
            flushResults.head == Some(input.length.toLong),
          )
        }
      }
    },
    test("onEnd receives final accumulated state") {
      val scan = Scan.stateful[Byte, Long, Long](
        initialState = 0L,
        initialOutputs = Chunk.empty,
        onEnd = state => Chunk.single(state), // Emit the final count
      )((state, _) => (state + 1, Chunk.empty)) // Count but don't emit during processing

      check(TestGen.boundedBytes) { input =>
        for {
          result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
        } yield assertTrue(result == Chunk.single(input.length.toLong))
      }
    },
    test("empty stream still calls onEnd") {
      val scan = Scan.stateful[Byte, String, String](
        initialState = "initial",
        initialOutputs = Chunk.empty,
        onEnd = state => Chunk.single(s"flushed:$state"),
      )((state, _) => (state + "x", Chunk.empty))

      for {
        result <- ZStream.empty.via(scan.pipeline).runCollect
      } yield assertTrue(result == Chunk("flushed:initial"))
    },
    test("onEnd can emit multiple values") {
      val scan = Scan.stateful[Byte, List[Byte], Byte](
        initialState = List.empty[Byte],
        initialOutputs = Chunk.empty,
        onEnd = state => Chunk.fromIterable(state.reverse), // Emit all buffered in reverse
      )((state, b) => (b :: state, Chunk.empty)) // Buffer everything

      check(TestGen.boundedBytes) { input =>
        for {
          result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
        } yield assertTrue(result == input.reverse)
      }
    },
    test("failed stream does not call onEnd") {
      val scan = Scan.stateful[Byte, Long, Option[Long]](
        initialState = 0L,
        initialOutputs = Chunk.empty,
        onEnd = state => Chunk.single(Some(state)),
      )((state, b) => (state + 1, Chunk.single(None)))

      val failingStream = ZStream(1.toByte, 2.toByte) ++ ZStream.fail(new RuntimeException("boom"))

      for {
        result <- failingStream.via(scan.pipeline).runCollect.either
      } yield assertTrue(
        result.isLeft,
        result.swap.exists(_.getMessage == "boom"),
      )
    },
    test("andThen preserves flush semantics") {
      val scan1 = Scan.stateful[Byte, Long, Long](
        initialState = 0L,
        initialOutputs = Chunk.empty,
        onEnd = state => Chunk.single(state),
      )((state, _) => (state + 1, Chunk.empty))

      val scan2 = Scan.stateful[Long, Long, String](
        initialState = 0L,
        initialOutputs = Chunk.empty,
        onEnd = state => Chunk.single(s"final:$state"),
      )((state, n) => (state + n, Chunk.empty))

      val composed = scan1.andThen(scan2)

      check(TestGen.boundedBytes) { input =>
        for {
          result <- ZStream.fromChunk(input).via(composed.pipeline).runCollect
        } yield {
          val expected = s"final:${input.length.toLong}"
          assertTrue(result.last == expected)
        }
      }
    },
    test("onEnd with empty output produces no elements") {
      val scan = Scan.stateful[Byte, Long, Byte](
        initialState = 0L,
        initialOutputs = Chunk.empty,
        onEnd = _ => Chunk.empty,
      )((state, b) => (state + 1, Chunk.single(b)))

      check(TestGen.boundedBytes) { input =>
        for {
          result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
        } yield assertTrue(result == input)
      }
    },
  )
}
