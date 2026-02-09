package graviton.core.scan

import graviton.core.bytes.*
import zio.*
import zio.stream.*
import zio.test.*

object TransducerKitSpec extends ZIOSpecDefault:

  private def bytes(s: String): Chunk[Byte] = Chunk.fromArray(s.getBytes("UTF-8"))

  def spec = suite("TransducerKit")(
    suite("counting")(
      test("blockCounter counts Chunk[Byte] elements") {
        val blocks       = List(bytes("a"), bytes("bb"), bytes("ccc"))
        val (summary, _) = Transducers.blockCounter.runChunk(blocks)
        assertTrue(summary.blockCount == 3L)
      },
      test("byteTotalChunked sums byte lengths") {
        val blocks       = List(bytes("a"), bytes("bb"), bytes("ccc"))
        val (summary, _) = Transducers.byteTotalChunked.runChunk(blocks)
        assertTrue(summary.totalBytes == 6L)
      },
    ),
    suite("statistics")(
      test("exponentialMovingAvg smooths values") {
        val (summary, out) = Transducers.exponentialMovingAvg(0.5).runChunk(List(10.0, 20.0, 30.0))
        assertTrue(summary.emaSamples == 3L) &&
        assertTrue(out.length == 3) &&
        assertTrue(out(0) == 10.0)
      }
    ),
    suite("deduplication")(
      test("dedup removes duplicates by key") {
        val (summary, out) = Transducers
          .dedup[String, String](identity)
          .runChunk(
            List("a", "b", "a", "c", "b", "d")
          )
        assertTrue(out == Chunk("a", "b", "c", "d")) &&
        assertTrue(summary.uniqueCount == 4L) &&
        assertTrue(summary.duplicateCount == 2L)
      }
    ),
    suite("batching")(
      test("batch groups elements") {
        val (summary, batches) = Transducers.batch[Int](3).runChunk(1 to 7)
        assertTrue(batches.length == 3) &&
        assertTrue(batches(0) == Chunk(1, 2, 3)) &&
        assertTrue(batches(1) == Chunk(4, 5, 6)) &&
        assertTrue(batches(2) == Chunk(7)) &&
        assertTrue(summary.batchCount == 3L)
      },
      test("groupBy groups consecutive equal keys") {
        val input             = List("a", "a", "b", "b", "b", "a", "c")
        val (summary, groups) = Transducers.groupBy[String, String](identity).runChunk(input)
        assertTrue(groups.length == 4) &&
        assertTrue(groups(0) == ("a", Chunk("a", "a"))) &&
        assertTrue(groups(1) == ("b", Chunk("b", "b", "b"))) &&
        assertTrue(groups(2) == ("a", Chunk("a"))) &&
        assertTrue(groups(3) == ("c", Chunk("c"))) &&
        assertTrue(summary.groupCount == 4L)
      },
    ),
    suite("composition")(
      test("blockCounter &&& byteTotalChunked: merged Record summary") {
        val telemetry    = Transducers.blockCounter &&& Transducers.byteTotalChunked
        val blocks       = List(bytes("hello"), bytes("world!"))
        val (summary, _) = telemetry.runChunk(blocks)
        assertTrue(summary.blockCount == 2L) &&
        assertTrue(summary.totalBytes == 11L)
      },
      test("dedup >>> counter: count only unique elements") {
        val pipeline       = Transducers.dedup[String, String](identity) >>> Transducer.counter[String]
        val input          = List("a", "b", "a", "c", "b", "d")
        val (summary, out) = pipeline.runChunk(input)
        assertTrue(out == Chunk(1L, 2L, 3L, 4L)) &&
        assertTrue(summary.count == 4L)
      },
      test("batch >>> map: process batches") {
        val pipeline = Transducers.batch[Int](3) >>> Transducer.map[Chunk[Int], Int](_.sum)
        val (_, out) = pipeline.runChunk(1 to 7)
        assertTrue(out == Chunk(6, 15, 7))
      },
    ),
    suite("ZIO integration")(
      test("toSink: get summary after consuming stream") {
        val counter = Transducers.blockCounter &&& Transducers.byteTotalChunked
        for
          result      <- ZStream
                           .fromIterable(List(bytes("aaa"), bytes("bb"), bytes("c")))
                           .run(counter.toSink)
          (summary, _) = result
        yield assertTrue(summary.blockCount == 3L) &&
          assertTrue(summary.totalBytes == 6L)
      },
      test("toPipeline: dedup as streaming filter") {
        for deduped <- ZStream
                         .fromIterable(List("a", "b", "a", "c", "b", "d"))
                         .via(Transducers.dedup[String, String](identity).toPipeline)
                         .runCollect
        yield assertTrue(deduped == Chunk("a", "b", "c", "d"))
      },
    ),
  )
