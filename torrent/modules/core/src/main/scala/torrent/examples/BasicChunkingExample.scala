package torrent.examples

import torrent.Length
import torrent.chunking.SimpleChunkPolicy

import zio.*
import zio.stream.*

/**
 * Basic example demonstrating chunking functionality
 */
object BasicChunkingExample extends ZIOAppDefault:

  def run = for {
    _ <- Console.printLine("Torrent Chunking Example")

    // Create some test data
    testData = "Hello, World! This is a test of the Torrent chunking system. "

    policy  = SimpleChunkPolicy.fixedSize(Length(64L))
    policy2 = torrent.chunking.RollingHashChunker.pipeline

    // Chunk the data
    chunks <- ZStream
                .fromChunk(Chunk(testData))
                .via(ZPipeline.utf8Encode)
                .repeat(Schedule.fixed(1.second))
                .take(1024 * 1024 * 10)
                .via(policy2)
                .runCollect

    _ <- Console.printLine(s"Original size: ${testData.length} bytes")
    _ <- Console.printLine(s"Number of chunks: ${chunks.size}")
    _ <- Console.printLine(s"Chunk sizes: ${chunks.map(_.size).mkString(", ")}")

    // Verify reconstruction
    reconstructed = chunks.map(_.data.asBase64String).mkString
    _            <- Console.printLine(s"Reconstruction successful: ${reconstructed == testData}")

  } yield ()
