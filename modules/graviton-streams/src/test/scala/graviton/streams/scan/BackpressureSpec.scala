package graviton.streams.scan

import zio.*
import zio.test.*
import zio.stream.*
import graviton.testkit.TestGen

/**
 * Tests for backpressure handling and ZIO Stream semantics.
 *
 * Ensures that:
 * - Backpressure is properly propagated through scans
 * - Slow consumers don't cause data loss
 * - Stream failures are properly handled
 */
object BackpressureSpec extends ZIOSpecDefault {

  // Reduce test samples to prevent OOM
  override def aspects = Chunk(TestAspect.samples(20))

  def spec = suite("Backpressure & Stream Semantics")(
    test("slow consumer does not drop scan outputs") {
      val scan     = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)
      val slowSink = ZSink.foreach((_: Long) => ZIO.sleep(1.millis))

      check(TestGen.boundedBytes) { input =>
        for {
          consumed <- Ref.make(0)
          _        <- ZStream
                        .fromChunk(input)
                        .via(scan.pipeline)
                        .mapZIO(n => consumed.update(_ + 1).as(n))
                        .run(slowSink)
          count    <- consumed.get
        } yield {
          val expected = if (input.isEmpty) 1 else input.length + 1 // Initial + per element
          assertTrue(count == expected)
        }
      }
    } @@ TestAspect.withLiveClock,
    test("backpressure preserves ordering") {
      val scan = Scan.identity[Byte]

      check(TestGen.boundedBytes) { input =>
        for {
          result <- ZStream
                      .fromChunk(input)
                      .via(scan.pipeline)
                      .throttleShape(1, 10.millis)(_ => 1)
                      .runCollect
        } yield assertTrue(result == input)
      }
    } @@ TestAspect.withLiveClock,
    test("scan handles upstream interruption gracefully") {
      val scan = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)

      for {
        fiber  <- ZStream
                    .fromIterable(0 to 100000) // Reduced from 1M to 100k
                    .map(_.toByte)
                    .via(scan.pipeline)
                    .runCollect
                    .fork
        _      <- ZIO.sleep(5.millis)
        _      <- fiber.interrupt
        result <- fiber.await
      } yield assertTrue(result.isInterrupted)
    } @@ TestAspect.withLiveClock,
    test("scan propagates upstream errors") {
      val scan  = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)
      val error = new RuntimeException("upstream error")

      val stream = ZStream(1.toByte, 2.toByte) ++ ZStream.fail(error)

      for {
        result <- stream.via(scan.pipeline).runCollect.either
      } yield assertTrue(
        result.isLeft,
        result.swap.exists(_ == error),
      )
    },
    test("scan outputs are emitted incrementally") {
      val scan = Scan.stateful[Byte, Long, Long](
        initialState = 0L,
        initialOutputs = Chunk.single(0L),
      ) { (state, _) =>
        val next = state + 1
        (next, Chunk.single(next))
      }

      for {
        ref       <- Ref.make(List.empty[Long])
        _         <- ZStream
                       .fromIterable(1 to 10)
                       .map(_.toByte)
                       .via(scan.pipeline)
                       .mapZIO(n => ref.update(n :: _))
                       .runDrain
        collected <- ref.get
      } yield {
        val reversed = collected.reverse
        // Should see incremental outputs: 0, 1, 2, ..., 10
        assertTrue(reversed == (0L to 10L).toList)
      }
    },
    test("multiple consumers can read scan outputs independently") {
      val scan  = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)
      val input = Chunk.fromIterable(1 to 100).map(_.toByte)

      for {
        results <- ZIO.scoped {
                     ZStream.fromChunk(input).via(scan.pipeline).broadcast(2, 16).flatMap { streams =>
                       val s1 = streams(0).runCollect
                       val s2 = streams(1).runCollect
                       s1.zipPar(s2)
                     }
                   }
        (r1, r2) = results
      } yield assertTrue(r1 == r2)
    },
    test("scan handles very small chunks efficiently") {
      val scan  = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)
      val input = Chunk.fromIterable(1 to 1000).map(_.toByte)

      // Feed one byte at a time
      for {
        result <- ZStream
                    .fromChunk(input)
                    .rechunk(1)
                    .via(scan.pipeline)
                    .runCollect
      } yield {
        val expectedLast = 1000L
        assertTrue(result.nonEmpty && result.last == expectedLast)
      }
    },
    test("scan handles very large chunks efficiently") {
      val scan  = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)
      val input = Chunk.fromIterable(1 to 10000).map(_.toByte)

      for {
        result <- ZStream
                    .fromChunk(input)
                    .rechunk(10000)
                    .via(scan.pipeline)
                    .runCollect
      } yield {
        val expectedLast = 10000L
        assertTrue(result.nonEmpty && result.last == expectedLast)
      }
    },
  )
}
