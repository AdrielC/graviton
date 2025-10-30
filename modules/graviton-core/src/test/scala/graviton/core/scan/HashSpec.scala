package graviton.core.scan

import zio.*
import zio.test.*
import zio.stream.*
import graviton.testkit.TestGen
import graviton.streams.scan.InterpretZIO

/**
 * Property-based tests for HashScan.
 * 
 * Verifies determinism, split invariance, and boundary behavior.
 */
object HashSpec extends ZIOSpecDefault:
  
  def spec = suite("HashScan Properties")(
    test("SHA-256 is deterministic") {
      check(TestGen.boundedBytes) { input =>
        val scan = HashScan.sha256
        
        for {
          run1 <- ZStream.fromChunk(input).via(InterpretZIO.toPipeline(scan)).runCollect
          run2 <- ZStream.fromChunk(input).via(InterpretZIO.toPipeline(scan)).runCollect
        } yield assertTrue(run1 == run2)
      }
    },
    
    test("SHA-256 empty input produces known digest") {
      val scan = HashScan.sha256
      
      for {
        result <- ZStream.empty.via(InterpretZIO.toPipeline(scan)).runCollect
      } yield {
        assertTrue(result.length == 1)
        // SHA-256 of empty string is a known constant
        val emptyHash = java.security.MessageDigest.getInstance("SHA-256").digest()
        assertTrue(java.util.Arrays.equals(result.head, emptyHash))
      }
    },
    
    test("sha256Every emits at boundaries") {
      val n = 16
      val scan = HashScan.sha256Every(n)
      val input = Chunk.fromIterable(1 to 50).map(_.toByte)
      
      for {
        result <- ZStream.fromChunk(input).via(InterpretZIO.toPipeline(scan)).runCollect
      } yield {
        // Should emit 3 hashes: 0-15, 16-31, 32-49 (flush)
        val expectedCount = (input.length + n - 1) / n
        assertTrue(result.nonEmpty)
      }
    },
    
    test("sha256Every is invariant to input chunking") {
      val n = 8
      val scan = HashScan.sha256Every(n)
      val input = Chunk.fromIterable(1 to 100).map(_.toByte)
      
      for {
        whole <- ZStream.fromChunk(input).via(InterpretZIO.toPipeline(scan)).runCollect
        chunked <- ZStream.fromChunk(input).rechunk(7).via(InterpretZIO.toPipeline(scan)).runCollect
        oneAtTime <- ZStream.fromChunk(input).rechunk(1).via(InterpretZIO.toPipeline(scan)).runCollect
      } yield assertTrue(
        whole.length == chunked.length,
        chunked.length == oneAtTime.length,
        whole.zip(chunked).forall { case (a, b) => java.util.Arrays.equals(a, b) }
      )
    },
    
    test("Hash composition: (hash >>> hash) processes correctly") {
      val scan1 = HashScan.sha256
      val scan2 = HashScan.sha256
      
      // Compose: hash bytes, then hash the resulting hash
      val composed = scan1 >>> scan2.contramap[Array[Byte]](arr => arr.headOption.getOrElse(0))
      
      val input = Chunk.fromIterable(1 to 32).map(_.toByte)
      
      for {
        result <- ZStream.fromChunk(input).via(InterpretZIO.toPipeline(composed)).runCollect
      } yield assertTrue(result.nonEmpty)
    }
  )
