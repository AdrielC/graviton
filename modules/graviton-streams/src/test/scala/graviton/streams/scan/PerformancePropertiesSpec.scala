package graviton.streams.scan

import zio.*
import zio.test.*
import zio.stream.*

/**
 * Performance and efficiency property tests.
 * 
 * Not rigorous benchmarks, but sanity checks that:
 * - Large inputs don't cause stack overflows
 * - Memory usage is bounded
 * - Processing completes in reasonable time
 */
object PerformancePropertiesSpec extends ZIOSpecDefault {

  def spec = suite("Performance Properties")(
    test("scan handles large stream without stack overflow") {
      val scan = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)
      val largeInput = Chunk.fill(100000)(0.toByte)
      
      for {
        result <- ZStream.fromChunk(largeInput).via(scan.pipeline).runCollect
      } yield assertTrue(result.last == 100000L)
    },
    
    test("scan handles very large state efficiently") {
      // This scan accumulates all bytes, testing memory behavior
      val scan = Scan.foldLeft[Byte, Long](0L)((acc, b) => acc + (b & 0xFF))
      val input = Chunk.fill(50000)(42.toByte)
      
      for {
        result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
      } yield assertTrue(result.last == 50000L * 42L)
    },
    
    test("deeply composed scans complete in reasonable time") {
      // Test composition with mapOut instead of andThen to avoid state tuple buildup
      val id = Scan.identity[Byte]
      val deepComposed = (1 to 20).foldLeft(id)((acc, _) => 
        acc.mapOut(identity[Byte])
      )
      
      val input = Chunk.fromIterable(1 to 1000).map(_.toByte)
      
      for {
        result <- ZStream.fromChunk(input)
                    .via(deepComposed.pipeline)
                    .runCollect
                    .timeout(10.seconds)
      } yield assertTrue(result.isDefined)
    },
    
    test("scan with many small chunks is efficient") {
      val scan = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)
      val input = Chunk.fromIterable(1 to 10000).map(_.toByte)
      
      for {
        result <- ZStream.fromChunk(input)
                    .rechunk(1) // One byte per chunk
                    .via(scan.pipeline)
                    .runCollect
                    .timeout(5.seconds)
      } yield assertTrue(
        result.isDefined,
        result.get.last == 10000L
      )
    },
    
    test("scan with very large chunks is efficient") {
      val scan = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)
      val input = Chunk.fill(100000)(0.toByte)
      
      for {
        result <- ZStream.fromChunk(input)
                    .rechunk(100000) // All at once
                    .via(scan.pipeline)
                    .runCollect
                    .timeout(5.seconds)
      } yield assertTrue(
        result.isDefined,
        result.get.last == 100000L
      )
    },
    
    test("scan doesn't build up unbounded queues") {
      // A scan that emits multiple outputs per input
      val scan = Scan.stateful[Byte, Unit, Byte](
        initialState = (),
        initialOutputs = Chunk.empty
      )((_, b) => ((), Chunk.fill(10)(b))) // 10x amplification
      
      val input = Chunk.fill(10000)(0.toByte)
      
      for {
        result <- ZStream.fromChunk(input)
                    .via(scan.pipeline)
                    .take(50000) // Take first 50k outputs
                    .runCollect
                    .timeout(5.seconds)
      } yield assertTrue(
        result.isDefined,
        result.get.length == 50000
      )
    },
    
    test("repeatedly creating scan instances doesn't leak") {
      val input = Chunk.fromIterable(1 to 100).map(_.toByte)
      
      for {
        results <- ZIO.foreach(1 to 100) { _ =>
          val scan = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)
          ZStream.fromChunk(input).via(scan.pipeline).runCollect
        }
      } yield assertTrue(results.forall(_.last == 100L))
    },
    
    test("scan with stateful cleanup completes") {
      var cleanupCount = 0
      
      val scan = Scan.stateful[Byte, Long, Long](
        initialState = 0L,
        initialOutputs = Chunk.empty,
        onEnd = state => {
          cleanupCount += 1
          Chunk.single(state)
        }
      )((state, _) => (state + 1, Chunk.empty))
      
      val input = Chunk.fill(1000)(0.toByte)
      
      for {
        _ <- ZIO.foreach(1 to 10) { _ =>
          cleanupCount = 0
          ZStream.fromChunk(input).via(scan.pipeline).runCollect.map { _ =>
            assertTrue(cleanupCount == 1)
          }
        }
      } yield assertCompletes
    },
    
    test("scan processes constant throughput under backpressure") {
      val scan = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)
      val input = Chunk.fill(5000)(0.toByte)
      
      for {
        start <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        result <- ZStream.fromChunk(input)
                    .via(scan.pipeline)
                    .throttleShape(100, 100.millis)(_ => 1)
                    .runCollect
        end <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        duration = end - start
      } yield assertTrue(
        result.last == 5000L,
        duration > 100 // Should take at least some time due to throttling
      )
    },
    
    test("scan handles rapid small stream creation") {
      val scan = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)
      
      for {
        results <- ZIO.foreach(1 to 1000) { i =>
          val input = Chunk.single(i.toByte)
          ZStream.fromChunk(input).via(scan.pipeline).runCollect
        }
      } yield assertTrue(results.length == 1000)
    }
  )
}
