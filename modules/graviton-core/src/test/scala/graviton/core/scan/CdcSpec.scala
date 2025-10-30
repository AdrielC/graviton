package graviton.core.scan

import zio.*
import zio.test.*
import zio.stream.*
import graviton.testkit.TestGen
import graviton.streams.scan.InterpretZIO

/**
 * Tests for Content-Defined Chunking invariance.
 * 
 * Verifies that CDC boundaries are reproducible regardless of
 * input chunking, and that min/avg/max constraints are respected.
 */
object CdcSpec extends ZIOSpecDefault:
  
  def spec = suite("CDC Properties")(
    test("Fixed chunking produces predictable boundaries") {
      val size = 16
      val scan = CdcScan.fixed(size)
      val input = Chunk.fromIterable(1 to 100).map(_.toByte)
      
      for {
        result <- ZStream.fromChunk(input).via(InterpretZIO.toPipeline(scan)).runCollect
      } yield {
        // Should produce chunks of exactly 'size' bytes
        val fullChunks = result.take(result.length - 1)
        assertTrue(
          fullChunks.forall(_ == size),
          result.sum == input.length
        )
      }
    },
    
    test("CDC boundaries are invariant to input chunking") {
      val scan = CdcScan.fastCdc(128, 256, 512)
      val input = Chunk.fromIterable(1 to 2000).map(_.toByte)
      
      for {
        whole <- ZStream.fromChunk(input).via(InterpretZIO.toPipeline(scan)).runCollect
        rechunked <- ZStream.fromChunk(input).rechunk(7).via(InterpretZIO.toPipeline(scan)).runCollect
        small <- ZStream.fromChunk(input).rechunk(1).via(InterpretZIO.toPipeline(scan)).runCollect
      } yield assertTrue(
        whole.sum == input.length,
        rechunked.sum == input.length,
        small.sum == input.length,
        whole.length == rechunked.length,
        rechunked.length == small.length
      )
    },
    
    test("CDC chunk sizes respect min/max constraints") {
      val min = 64
      val avg = 128
      val max = 256
      val scan = CdcScan.fastCdc(min, avg, max)
      val input = Chunk.fill(5000)(42.toByte)
      
      for {
        result <- ZStream.fromChunk(input).via(InterpretZIO.toPipeline(scan)).runCollect
      } yield {
        val outOfBounds = result.filter(size => size < min || size > max)
        assertTrue(
          outOfBounds.isEmpty,
          result.sum == input.length
        )
      }
    },
    
    test("CDC with repeating pattern") {
      val scan = CdcScan.fastCdc(64, 128, 256)
      val pattern = Chunk.fromArray(Array[Byte](1, 2, 3, 4, 5))
      val input = Chunk.fromIterable(List.fill(200)(pattern).flatten)
      
      for {
        result <- ZStream.fromChunk(input).via(InterpretZIO.toPipeline(scan)).runCollect
      } yield assertTrue(
        result.sum == input.length,
        result.nonEmpty
      )
    },
    
    test("CDC composition with hashing") {
      val cdc = CdcScan.fastCdc(128, 256, 512)
      val hash = HashScan.sha256
      
      // Process bytes through CDC, then hash boundary lengths
      val composed = cdc >>> hash.contramap[Int](_.toByte)
      
      val input = Chunk.fromIterable(1 to 1000).map(_.toByte)
      
      for {
        result <- ZStream.fromChunk(input).via(InterpretZIO.toPipeline(composed)).runCollect
      } yield assertTrue(result.nonEmpty)
    }
  )
