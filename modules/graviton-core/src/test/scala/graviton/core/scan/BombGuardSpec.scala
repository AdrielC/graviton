package graviton.core.scan

import zio.*
import zio.stream.*
import zio.test.*

object BombGuardSpec extends ZIOSpecDefault:

  override def spec =
    suite("BombGuard")(
      test("passes through data under the limit") {
        val guard             = BombGuard(maxBytes = 1000L)
        val input             = List(Chunk.fromArray(Array.fill(100)(1.toByte)), Chunk.fromArray(Array.fill(200)(2.toByte)))
        val (summary, output) = guard.runChunk(input)
        assertTrue(
          output.length == 2,
          output.flatMap(_.toList).length == 300,
        )
      },
      test("rejects data over the limit") {
        val guard       = BombGuard(maxBytes = 100L)
        val input       = List(
          Chunk.fromArray(Array.fill(60)(1.toByte)),
          Chunk.fromArray(Array.fill(60)(2.toByte)), // This pushes past 100
          Chunk.fromArray(Array.fill(30)(3.toByte)),
        )
        val (_, output) = guard.runChunk(input)
        assertTrue(
          output.length == 1, // Only the first chunk passes
          output(0).length == 60,
        )
      },
      test("single chunk exactly at limit passes") {
        val guard       = BombGuard(maxBytes = 50L)
        val input       = List(Chunk.fromArray(Array.fill(50)(1.toByte)))
        val (_, output) = guard.runChunk(input)
        assertTrue(output.length == 1, output(0).length == 50)
      },
      test("single chunk over limit is rejected") {
        val guard       = BombGuard(maxBytes = 50L)
        val input       = List(Chunk.fromArray(Array.fill(51)(1.toByte)))
        val (_, output) = guard.runChunk(input)
        assertTrue(output.isEmpty)
      },
      test("compiles to ZPipeline and streams correctly") {
        val guard    = BombGuard(maxBytes = 100L)
        val pipeline = guard.toPipeline
        for result <- ZStream(
                        Chunk.fromArray(Array.fill(40)(1.toByte)),
                        Chunk.fromArray(Array.fill(40)(2.toByte)),
                        Chunk.fromArray(Array.fill(40)(3.toByte)), // Would push to 120, rejected
                      ).via(pipeline).runCollect
        yield assertTrue(result.length == 2) // Two chunks pass, third dropped
      },
      test("composes with countBytes via >>>") {
        val safePipeline = BombGuard(maxBytes = 200L) >>> IngestPipeline.countBytes
        val input        = List(
          Chunk.fromArray(Array.fill(100)(1.toByte)),
          Chunk.fromArray(Array.fill(100)(2.toByte)),
          Chunk.fromArray(Array.fill(100)(3.toByte)), // rejected
        )
        val (_, output)  = safePipeline.runChunk(input)
        // countBytes passes through, so we get 2 chunks
        assertTrue(output.length == 2)
      },
      test("empty input produces no output and no rejection") {
        val guard       = BombGuard(maxBytes = 100L)
        val (_, output) = guard.runChunk(List.empty)
        assertTrue(output.isEmpty)
      },
    )
