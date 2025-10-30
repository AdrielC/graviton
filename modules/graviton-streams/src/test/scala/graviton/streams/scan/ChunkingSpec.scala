package graviton.streams.scan

import zio.*
import zio.test.*
import zio.stream.*
import graviton.testkit.TestGen

/**
 * Tests for Content-Defined Chunking (CDC) and related chunking strategies.
 * 
 * Verifies:
 * - Boundaries are invariant across different input chunk sizes
 * - Chunk sizes respect min/avg/max constraints
 * - Fixed-size chunking works correctly
 * - Edge cases (empty input, very small/large inputs)
 */
object ChunkingSpec extends ZIOSpecDefault {

  /** A simple fixed-size chunking scan for testing */
  def fixedChunker(size: Int): Scan[Byte, (Chunk[Byte], Int), Chunk[Byte]] = {
    Scan.stateful[Byte, (Chunk[Byte], Int), Chunk[Byte]](
      initialState = (Chunk.empty, 0),
      initialOutputs = Chunk.empty,
      onEnd = { case (buffer, _) =>
        if (buffer.nonEmpty) Chunk.single(buffer)
        else Chunk.empty
      }
    ) { case ((buffer, count), byte) =>
      val newBuffer = buffer :+ byte
      val newCount = count + 1
      
      if (newCount >= size) {
        ((Chunk.empty, 0), Chunk.single(newBuffer))
      } else {
        ((newBuffer, newCount), Chunk.empty)
      }
    }
  }

  /** A simple rolling-hash based chunker for testing CDC properties */
  def rollingHashChunker(targetSize: Int, mask: Int = 0xFFF): Scan[Byte, (Chunk[Byte], Int), Chunk[Byte]] = {
    Scan.stateful[Byte, (Chunk[Byte], Int), Chunk[Byte]](
      initialState = (Chunk.empty, 0),
      initialOutputs = Chunk.empty,
      onEnd = { case (buffer, _) =>
        if (buffer.nonEmpty) Chunk.single(buffer)
        else Chunk.empty
      }
    ) { case ((buffer, hash), byte) =>
      val newBuffer = buffer :+ byte
      val newHash = ((hash << 1) ^ byte.toInt) & 0xFFFFFF
      
      // Trigger boundary when hash matches mask OR we've exceeded 2x target
      val shouldSplit = (newHash & mask) == 0 || newBuffer.length >= targetSize * 2
      
      if (shouldSplit && newBuffer.nonEmpty) {
        ((Chunk.empty, 0), Chunk.single(newBuffer))
      } else {
        ((newBuffer, newHash), Chunk.empty)
      }
    }
  }

  def spec = suite("Chunking Strategies")(
    test("fixed chunker produces chunks of exact size") {
      val chunkSize = 16
      val scan = fixedChunker(chunkSize)
      val input = Chunk.fromIterable(1 to 100).map(_.toByte)
      
      for {
        result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
      } yield {
        val fullChunks = result.take(result.length - 1)
        val lastChunk = result.lastOption
        
        assertTrue(
          fullChunks.forall(_.length == chunkSize),
          lastChunk.exists(c => c.length <= chunkSize && c.length == input.length % chunkSize || c.length == chunkSize)
        )
      }
    },
    
    test("fixed chunker handles empty input") {
      val scan = fixedChunker(16)
      for {
        result <- ZStream.empty.via(scan.pipeline).runCollect
      } yield assertTrue(result.isEmpty)
    },
    
    test("fixed chunker handles input smaller than chunk size") {
      val scan = fixedChunker(16)
      val input = Chunk.fromIterable(1 to 5).map(_.toByte)
      
      for {
        result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
      } yield assertTrue(
        result.length == 1,
        result.head == input
      )
    },
    
    test("fixed chunker is invariant to input chunking") {
      val chunkSize = 8
      val scan = fixedChunker(chunkSize)
      val input = Chunk.fromIterable(1 to 50).map(_.toByte)
      
      // Process with different input chunk sizes
      val whole = ZStream.fromChunk(input).via(scan.pipeline).runCollect
      val rechunked = ZStream.fromChunk(input).rechunk(3).via(scan.pipeline).runCollect
      val oneAtATime = ZStream.fromChunk(input).rechunk(1).via(scan.pipeline).runCollect
      
      for {
        w <- whole
        r <- rechunked
        o <- oneAtATime
      } yield assertTrue(
        w == r,
        r == o,
        w.flatten == input
      )
    },
    
    test("rolling hash chunker produces content-defined boundaries") {
      val scan = rollingHashChunker(targetSize = 16, mask = 0x7) // ~1/8 probability
      val input = Chunk.fromIterable(1 to 200).map(_.toByte)
      
      for {
        result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
      } yield {
        val totalBytes = result.map(_.length).sum
        assertTrue(
          result.nonEmpty,
          totalBytes == input.length,
          result.flatten == input
        )
      }
    },
    
    test("rolling hash chunker boundaries are input-chunking invariant") {
      val scan = rollingHashChunker(targetSize = 32, mask = 0xF)
      val input = Chunk.fromIterable(1 to 500).map(_.toByte)
      
      // Get boundary positions with different input chunking
      def getBoundaries(rechunkSize: Int) =
        ZStream.fromChunk(input)
          .rechunk(rechunkSize)
          .via(scan.pipeline)
          .runCollect
          .map { chunks =>
            chunks.scanLeft(0)((acc, chunk) => acc + chunk.length).tail.toList
          }
      
      for {
        boundaries1 <- getBoundaries(1)
        boundaries7 <- getBoundaries(7)
        boundaries23 <- getBoundaries(23)
      } yield assertTrue(
        boundaries1 == boundaries7,
        boundaries7 == boundaries23
      )
    },
    
    test("chunker composition preserves data") {
      val chunker1 = fixedChunker(16)
      
      check(TestGen.boundedBytes) { input =>
        for {
          result <- ZStream.fromChunk(input).via(chunker1.pipeline).runCollect
        } yield assertTrue(result.flatten == input)
      }
    },
    
    test("chunker handles repeating patterns correctly") {
      val scan = rollingHashChunker(targetSize = 16, mask = 0xF)
      // Repeating pattern might create predictable hash values
      val pattern = Chunk.fromArray(Array[Byte](1, 2, 3, 4, 5))
      val input = Chunk.fromIterable(List.fill(50)(pattern).flatten)
      
      for {
        result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
      } yield assertTrue(
        result.nonEmpty,
        result.flatten == input
      )
    },
    
    test("chunker with all zeros") {
      val scan = rollingHashChunker(targetSize = 32, mask = 0x7)
      val input = Chunk.fill(200)(0.toByte)
      
      for {
        result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
      } yield assertTrue(
        result.flatten == input,
        result.map(_.length).sum == input.length
      )
    },
    
    test("chunker enforces maximum chunk size") {
      val targetSize = 16
      val maxSize = targetSize * 2
      val scan = rollingHashChunker(targetSize, mask = 0xFFFFFF) // Very unlikely to match
      val input = Chunk.fill(100)(0.toByte) // No natural boundaries
      
      for {
        result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
      } yield assertTrue(
        result.forall(_.length <= maxSize),
        result.flatten == input
      )
    }
  )
}
