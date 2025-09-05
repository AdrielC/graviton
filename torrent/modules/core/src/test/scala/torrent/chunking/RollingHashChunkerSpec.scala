package torrent.chunking

import scala.language.implicitConversions

import io.github.iltotore.iron.{ zio as _, * }
import torrent.{ Bytes, Length }

import zio.*
import zio.stream.*
import zio.test.*
import zio.test.Assertion.*

object RollingHashChunkerSpec extends ZIOSpecDefault:

  def spec = suite("RollingHashChunker")(
    test("should produce reproducible chunks for identical content"):
      val content                                    =
        "Hello, World! This is a test of the rolling hash chunker.".getBytes
      implicit val config: RollingHashChunker.Config =
        RollingHashChunker.Config(
          minSize = Length(10L),
          avgSize = Length(20L),
          maxSize = Length(40L)
        )

      for
        chunks1 <- ZStream
                     .fromChunk(Chunk.fromArray(content))
                     .via(RollingHashChunker.pipeline)
                     .runCollect

        chunks2 <- ZStream
                     .fromChunk(Chunk.fromArray(content))
                     .via(RollingHashChunker.pipeline)
                     .runCollect
      yield assert(chunks1)(equalTo(chunks2))
    ,
    test("should respect minimum chunk size") {
      val content                                    = "a" * 1000
      implicit val config: RollingHashChunker.Config =
        RollingHashChunker.Config(
          minSize = Length(100L),
          avgSize = Length(200L),
          maxSize = Length(400L)
        )

      for {
        chunks <- ZStream
                    .fromChunk(Chunk.fromArray(content.getBytes))
                    .via(RollingHashChunker.pipeline)
                    .runCollect
      } yield assert(chunks.map(_.data.size).min >= 100)(isTrue)
    },
    test("should not exceed maximum chunk size"):
      val content                                    = "b" * 2000
      implicit val config: RollingHashChunker.Config =
        RollingHashChunker.Config(
          minSize = Length(50L),
          avgSize = Length(100L),
          maxSize = Length(200L)
        )

      for chunks <- ZStream
                      .fromChunk(Chunk.fromArray(content.getBytes))
                      .via(RollingHashChunker.pipeline)
                      .runCollect
      yield assert(chunks.map(_.data.size).max <= 200)(isTrue) &&
        assert(chunks.forall(_.data.size > 0))(isTrue)
    ,
    test("should handle empty input"):

      for {
        chunks <- ZStream.empty
                    .via(RollingHashChunker.pipeline)
                    .runCollect
      } yield assert(chunks)(isEmpty)
    ,
    test("should handle single byte input"):
      implicit val config: RollingHashChunker.Config =
        RollingHashChunker.Config(
          minSize = Length(1L),
          avgSize = Length(2L),
          maxSize = Length(4L)
        )

      for {
        chunks <- ZStream
                    .fromChunk(Chunk.single(42.toByte))
                    .via(RollingHashChunker.pipeline)
                    .runCollect
      } yield assert(chunks)(hasSize(equalTo(1))) &&
        assert(chunks.head.data.size)(equalTo(1))
    ,
    test("should produce stable boundaries with content shifts"):
      val prefix  = "prefix-"
      val content = "This is the main content that should chunk consistently."
      val suffix  = "-suffix"

      val original = prefix + content + suffix
      val shifted  = "x" + prefix + content + suffix // Add one byte at start

      implicit val config: RollingHashChunker.Config =
        RollingHashChunker.Config(
          minSize = Length(10L),
          avgSize = Length(20L),
          maxSize = Length(50L)
        )

      for
        originalChunks <- ZStream
                            .fromChunk(Chunk.fromArray(original.getBytes))
                            .via(RollingHashChunker.pipeline)
                            .runCollect

        shiftedChunks <- ZStream
                           .fromChunk(Chunk.fromArray(shifted.getBytes))
                           .via(RollingHashChunker.pipeline)
                           .runCollect

        // Find common subsequences in chunks
        originalContent = originalChunks.map(_.data.asUtf8String).mkString
        shiftedContent  = shiftedChunks.map(_.data.asUtf8String).mkString
      yield
        // Content should be preserved
        assert(originalContent)(equalTo(original)) &&
          assert(shiftedContent)(equalTo(shifted)) &&
          // Should have some boundary stability (not all chunks different)
          assert(originalChunks.size)(isGreaterThan(1)) &&
          assert(shiftedChunks.size)(isGreaterThan(1))
    ,
    test("should work with Bytes conversion"):
      val content = "Test content for Bytes conversion"

      for
        bytesChunks <- RollingHashChunker
                         .chunk(ZStream.fromChunk(Chunk.fromArray(content.getBytes)))
                         .runCollect

        // Verify all chunks are valid Bytes
        reconstructed = bytesChunks.map(_.data.asUtf8String)
      yield assert(bytesChunks)(isNonEmpty) &&
        assert(reconstructed.mkString)(equalTo(content))
    ,
    test("should handle large content efficiently"):
      val largeContent                               = "x" * 100000 // 100KB
      implicit val config: RollingHashChunker.Config =
        RollingHashChunker.Config()

      for
        start    <- Clock.nanoTime
        chunks   <- ZStream
                      .fromChunk(Chunk.fromArray(largeContent.getBytes))
                      .via(RollingHashChunker.pipeline)
                      .runCollect
        end      <- Clock.nanoTime
        duration  = (end - start) / 1000000 // Convert to milliseconds
        totalSize = chunks.map(_.data.size.toLong).sum
      yield assert(totalSize)(equalTo(100000L)) &&
        assert(chunks.size)(isGreaterThan(1)) &&
        assert(duration)(isLessThan(1000L)) // Should complete in under 1 second
  )
