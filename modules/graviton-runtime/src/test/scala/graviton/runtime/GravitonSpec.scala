package graviton.runtime

import graviton.core.keys.BinaryKey
import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets

object GravitonSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("Graviton facade")(
      test("ingestBytes -> retrieve round-trip") {
        val data = Chunk.fromArray("facade test data".getBytes(StandardCharsets.UTF_8))
        for
          g        <- Graviton.inMemory(chunkSize = 64)
          result   <- g.ingestBytes(data)
          readBack <- g.retrieve(result.key)
        yield assertTrue(readBack == data)
      },
      test("ingestBytes -> verify returns true") {
        val data = Chunk.fromArray("verify me".getBytes(StandardCharsets.UTF_8))
        for
          g        <- Graviton.inMemory(chunkSize = 64)
          result   <- g.ingestBytes(data)
          verified <- g.verify(result.key)
        yield assertTrue(verified)
      },
      test("stat returns metadata after ingest") {
        val data = Chunk.fromArray("stat check".getBytes(StandardCharsets.UTF_8))
        for
          g       <- Graviton.inMemory(chunkSize = 64)
          result  <- g.ingestBytes(data)
          statOpt <- g.stat(result.key)
        yield assertTrue(
          statOpt.isDefined,
          statOpt.get.size.value == data.length.toLong,
        )
      },
      test("delete removes manifest") {
        val data = Chunk.fromArray("delete me".getBytes(StandardCharsets.UTF_8))
        for
          g          <- Graviton.inMemory(chunkSize = 64)
          result     <- g.ingestBytes(data)
          statBefore <- g.stat(result.key)
          _          <- g.delete(result.key)
          statAfter  <- g.stat(result.key)
        yield assertTrue(statBefore.isDefined, statAfter.isEmpty)
      },
      test("ingest stats are populated") {
        val data = Chunk.fromArray(Array.tabulate(500)(i => (i % 100).toByte))
        for
          g      <- Graviton.inMemory(chunkSize = 128)
          result <- g.ingestBytes(data)
        yield assertTrue(
          result.stats.totalBytes == 500L,
          result.stats.blockCount > 0,
          result.stats.freshBlocks > 0,
        )
      },
      test("duplicate ingest shows dedup in stats") {
        val data = Chunk.fromArray("dedup via facade".getBytes(StandardCharsets.UTF_8))
        for
          g       <- Graviton.inMemory(chunkSize = 64)
          result1 <- g.ingestBytes(data)
          result2 <- g.ingestBytes(data)
        yield assertTrue(
          result1.key == result2.key,
          result2.stats.duplicateBlocks > 0,
        )
      },
      test("stream provides same bytes as retrieve") {
        val data = Chunk.fromArray(Array.tabulate(1000)(i => (i % 200).toByte))
        for
          g        <- Graviton.inMemory(chunkSize = 256)
          result   <- g.ingestBytes(data)
          streamed <- g.stream(result.key).runCollect
          fetched  <- g.retrieve(result.key)
        yield assertTrue(streamed == fetched)
      },
    )
