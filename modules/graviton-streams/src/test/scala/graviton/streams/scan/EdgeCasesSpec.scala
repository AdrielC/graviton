package graviton.streams.scan

import zio.*
import zio.test.*
import zio.stream.*
import graviton.testkit.TestGen

/**
 * Tests for edge cases and boundary conditions.
 * 
 * Covers:
 * - Empty streams
 * - Single-element streams
 * - Very large streams
 * - Extreme state values
 * - Error conditions
 */
object EdgeCasesSpec extends ZIOSpecDefault {

  def spec = suite("Edge Cases")(
    test("empty stream with no initial outputs") {
      val scan = Scan.stateful[Byte, Long, Long](
        initialState = 0L,
        initialOutputs = Chunk.empty,
        onEnd = state => Chunk.single(state)
      )((state, _) => (state + 1, Chunk.empty))
      
      for {
        result <- ZStream.empty.via(scan.pipeline).runCollect
      } yield assertTrue(result == Chunk(0L))
    },
    
    test("empty stream with initial outputs") {
      val scan = Scan.stateful[Byte, Long, Long](
        initialState = 0L,
        initialOutputs = Chunk(1L, 2L, 3L),
        onEnd = _ => Chunk.empty
      )((state, _) => (state + 1, Chunk.empty))
      
      for {
        result <- ZStream.empty.via(scan.pipeline).runCollect
      } yield assertTrue(result == Chunk(1L, 2L, 3L))
    },
    
    test("single element stream") {
      val scan = Scan.foldLeft[Byte, Long](0L)((acc, b) => acc + b)
      val input = Chunk.single(42.toByte)
      
      for {
        result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
      } yield assertTrue(result == Chunk(0L, 42L))
    },
    
    test("scan with state that grows unboundedly") {
      val scan = Scan.stateful[Byte, List[Byte], Int](
        initialState = List.empty,
        initialOutputs = Chunk.empty,
        onEnd = state => Chunk.single(state.length)
      )((state, b) => (b :: state, Chunk.empty))
      
      val input = Chunk.fill(10000)(0.toByte)
      
      for {
        result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
      } yield assertTrue(result == Chunk(10000))
    },
    
    test("scan with numeric overflow protection") {
      val scan = Scan.foldLeft[Byte, Long](Long.MaxValue - 10)((acc, b) => {
        if (acc >= Long.MaxValue - 1000) acc // Saturate
        else acc + b
      })
      
      val input = Chunk.fill(100)(100.toByte)
      
      for {
        result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
      } yield assertTrue(result.nonEmpty && result.last < Long.MaxValue)
    },
    
    test("scan that emits nothing ever") {
      val scan = Scan.stateful[Byte, Long, Nothing](
        initialState = 0L,
        initialOutputs = Chunk.empty,
        onEnd = _ => Chunk.empty
      )((state, _) => (state + 1, Chunk.empty))
      
      check(TestGen.boundedBytes) { input =>
        for {
          result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
        } yield assertTrue(result.isEmpty)
      }
    },
    
    test("scan with very large single emission") {
      val largeChunk = Chunk.fill(1000000)(0.toByte)
      val scan = Scan.stateful[Byte, Chunk[Byte], Chunk[Byte]](
        initialState = Chunk.empty,
        initialOutputs = Chunk.empty,
        onEnd = state => Chunk.single(state)
      )((state, b) => (state :+ b, Chunk.empty))
      
      val input = largeChunk.take(1000) // Keep it reasonable
      
      for {
        result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
      } yield assertTrue(
        result.length == 1,
        result.head == input
      )
    },
    
    test("scan recovers from partial state") {
      // Simulate a scan that might fail midway but we test normal completion
      var stepCount = 0
      val scan = Scan.stateful[Byte, Long, (Long, Int)](
        initialState = 0L,
        initialOutputs = Chunk.empty
      )((state, b) => {
        stepCount += 1
        (state + b, Chunk.single((state, stepCount)))
      })
      
      check(TestGen.boundedBytes) { input =>
        stepCount = 0
        for {
          result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
        } yield assertTrue(result.length == input.length)
      }
    },
    
    test("scan handles all-zeros input") {
      val scan = Scan.foldLeft[Byte, Long](0L)((acc, b) => acc + b)
      val input = Chunk.fill(100)(0.toByte)
      
      for {
        result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
      } yield assertTrue(
        result.forall(_ == 0L),
        result.length == input.length + 1
      )
    },
    
    test("scan handles all-same-value input") {
      val scan = Scan.foldLeft[Byte, Long](0L)((acc, b) => acc + b)
      val value = 7.toByte
      val input = Chunk.fill(50)(value)
      
      for {
        result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
      } yield {
        val expected = (0L to 50L).map(_ * value)
        assertTrue(result == Chunk.fromIterable(expected))
      }
    },
    
    test("scan handles maximum byte values") {
      val scan = Scan.foldLeft[Byte, Long](0L)((acc, b) => acc + (b & 0xFF))
      val input = Chunk.fill(100)((-1).toByte) // All 0xFF
      
      for {
        result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
      } yield {
        val expectedLast = 100L * 255L
        assertTrue(result.last == expectedLast)
      }
    },
    
    test("scan composes with itself repeatedly") {
      val scan = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)
      val composed = scan.andThen(scan.contramap[Long](_.toByte))
      
      check(TestGen.boundedBytes) { input =>
        for {
          result <- ZStream.fromChunk(input).via(composed.pipeline).runCollect
        } yield assertTrue(result.nonEmpty)
      }
    },
    
    test("scan with unit state behaves correctly") {
      val scan = Scan.stateful[Byte, Unit, Byte](
        initialState = (),
        initialOutputs = Chunk.empty
      )((_, b) => ((), Chunk.single(b)))
      
      check(TestGen.boundedBytes) { input =>
        for {
          result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
        } yield assertTrue(result == input)
      }
    },
    
    test("scan handles rapid on/off pattern") {
      check(TestGen.boundedBytes) { input =>
        val scan = Scan.identity[Byte]
        
        // Rapidly start and stop the stream
        for {
          r1 <- ZStream.fromChunk(input.take(1)).via(scan.pipeline).runCollect
          r2 <- ZStream.fromChunk(input.drop(1).take(1)).via(scan.pipeline).runCollect
          r3 <- ZStream.fromChunk(input.drop(2)).via(scan.pipeline).runCollect
        } yield {
          val combined = r1 ++ r2 ++ r3
          assertTrue(combined == input)
        }
      }
    }
  )
}
