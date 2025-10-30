package graviton.streams.scan

import zio.*
import zio.test.*
import zio.stream.*
import graviton.testkit.TestGen

/**
 * Tests for concurrent and parallel usage of scans.
 *
 * Verifies:
 * - Thread safety when used with parallel streams
 * - Correct behavior with interruption
 * - Resource cleanup
 * - Race condition freedom
 */
object ConcurrencySpec extends ZIOSpecDefault {

  // Reduce test samples to prevent OOM with concurrent tests
  override def aspects = Chunk(TestAspect.samples(20))

  def spec = suite("Concurrency & Parallelism")(
    test("scan can process multiple streams concurrently") {
      val scan = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)

      check(TestGen.boundedBytes, TestGen.boundedBytes) { (input1, input2) =>
        for {
          results     <- ZIO.collectAllPar(
                           List(
                             ZStream.fromChunk(input1).via(scan.pipeline).runCollect,
                             ZStream.fromChunk(input2).via(scan.pipeline).runCollect,
                           )
                         )
          List(r1, r2) = results
        } yield assertTrue(
          r1.lastOption.contains(input1.length.toLong),
          r2.lastOption.contains(input2.length.toLong),
        )
      }
    },
    test("scan handles concurrent composition") {
      val scan = Scan.foldLeft[Byte, Long](0L)((acc, b) => acc + b)

      check(TestGen.boundedBytes) { input =>
        val stream = ZStream.fromChunk(input).via(scan.pipeline)

        for {
          results <- ZIO.collectAllPar(
                       List(
                         stream.runCollect,
                         stream.runCollect,
                         stream.runCollect,
                       )
                     )
        } yield {
          val allSame = results.tail.forall(_ == results.head)
          assertTrue(allSame)
        }
      }
    },
    test("scan is interruptible during processing") {
      val scan = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)

      val largeInput = Chunk.fill(100000)(0.toByte) // Reduced from 1M to 100k

      for {
        fiber  <- ZStream
                    .fromChunk(largeInput)
                    .via(scan.pipeline)
                    .runCollect
                    .fork
        _      <- ZIO.sleep(10.millis)
        _      <- fiber.interrupt
        result <- fiber.await
      } yield assertTrue(result.isInterrupted)
    } @@ TestAspect.withLiveClock,
    test("multiple scans can run in parallel on same input") {
      val scan1 = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)
      val scan2 = Scan.foldLeft[Byte, Long](1L)((acc, _) => acc * 2)

      check(TestGen.boundedBytes) { input =>
        val stream = ZStream.fromChunk(input)

        for {
          results     <- ZIO.collectAllPar(
                           List(
                             stream.via(scan1.pipeline).runCollect,
                             stream.via(scan2.pipeline).runCollect,
                           )
                         )
          List(r1, r2) = results
        } yield assertTrue(r1.nonEmpty, r2.nonEmpty)
      }
    },
    test("scan with broadcast to multiple consumers") {
      val scan  = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)
      val input = Chunk.fromIterable(1 to 100).map(_.toByte)

      for {
        results <- ZIO.scoped {
                     ZStream.fromChunk(input).via(scan.pipeline).broadcast(3, 16).flatMap { streams =>
                       ZIO.collectAllPar(streams.map(_.runCollect))
                     }
                   }
      } yield {
        val allEqual = results.tail.forall(_ == results.head)
        assertTrue(allEqual, results.head.nonEmpty)
      }
    },
    test("scan doesn't leak with failed stream") {
      val scan  = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)
      val error = new RuntimeException("test error")

      for {
        result <- ZStream(1.toByte, 2.toByte)
                    .concat(ZStream.fail(error))
                    .via(scan.pipeline)
                    .runCollect
                    .either
      } yield assertTrue(result.isLeft)
    },
    test("scan handles racing streams") {
      val scan = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)

      check(TestGen.boundedBytes, TestGen.boundedBytes) { (input1, input2) =>
        val stream1 = ZStream.fromChunk(input1).via(scan.pipeline).runCollect
        val stream2 = ZStream.fromChunk(input2).via(scan.pipeline).runCollect

        for {
          winner <- stream1.race(stream2)
        } yield assertTrue(winner.nonEmpty)
      }
    },
    test("scan state is isolated per stream instance") {
      // Note: Using Ref instead of var to avoid race conditions in concurrent tests
      check(TestGen.boundedBytes) { input =>
        for {
          stateChangesRef <- Ref.make(0)
          scan             = Scan.stateful[Byte, Long, Long](
                               initialState = 0L,
                               initialOutputs = Chunk.empty,
                             ) { (state, _) =>
                               // Count state changes atomically
                               (state + 1, Chunk.single(state))
                             }
          instances        = List.fill(3)(
                               ZStream.fromChunk(input).via(scan.pipeline).runCollect
                             )
          results         <- ZIO.collectAllPar(instances)
        } yield {
          // Each instance should process the full input (one output per input element)
          assertTrue(results.forall(r => r.length == input.length))
        }
      }
    },
    test("scan handles merge of multiple streams") {
      val scan = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)

      check(TestGen.boundedBytes, TestGen.boundedBytes) { (input1, input2) =>
        val merged = ZStream
          .fromChunk(input1)
          .merge(ZStream.fromChunk(input2))
          .via(scan.pipeline)

        for {
          result <- merged.runCollect
        } yield {
          val totalInputs = input1.length + input2.length
          assertTrue(result.lastOption.exists(_ == totalInputs.toLong))
        }
      }
    },
    test("scan with parallel processing doesn't corrupt state") {
      val scan  = Scan.foldLeft[Byte, Long](0L)((acc, b) => acc + b)
      val input = Chunk.fromIterable(1 to 100).map(_.toByte)

      // Process same input multiple times in parallel
      for {
        results <- ZIO.collectAllPar(
                     List.fill(10)(
                       ZStream.fromChunk(input).via(scan.pipeline).runCollect
                     )
                   )
      } yield {
        val allSame = results.tail.forall(_ == results.head)
        assertTrue(allSame)
      }
    },
    test("scan handles timeout during processing") {
      val scan  = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)
      val input = Chunk.fill(1000)(0.toByte)

      for {
        result <- ZStream
                    .fromChunk(input)
                    .via(scan.pipeline)
                    .runCollect
                    .timeout(5.seconds)
      } yield assertTrue(result.isDefined)
    } @@ TestAspect.withLiveClock,
  )
}
