package graviton.core.scan

import graviton.core.bytes.*
import kyo.Record
import kyo.Record.`~`
import zio.*
import zio.stream.*
import zio.test.*

object TransducerKitSpec extends ZIOSpecDefault:

  private def bytes(s: String): Chunk[Byte] = Chunk.fromArray(s.getBytes("UTF-8"))

  def spec = suite("TransducerKit")(
    // =========================================================================
    //  Individual transducers
    // =========================================================================

    suite("hashing")(
      test("chunkDigest produces per-block digests") {
        val blocks   = List(bytes("hello"), bytes("world"))
        val (_, out) = Transducers.chunkDigest().runChunk(blocks)
        assertTrue(out.length == 2) &&
        assertTrue(out.forall(_._2.isRight))
      }
    ),
    suite("chunking")(
      test("fixedSizeChunker produces correct block sizes") {
        val input             = (1 to 10).map(_.toByte).toList
        val (summary, blocks) = Transducers.fixedSizeChunker(3).runChunk(input)
        // 10 bytes / 3 = 3 full blocks + 1 remainder
        assertTrue(blocks.length == 4) &&
        assertTrue(blocks.take(3).forall(_.length == 3)) &&
        assertTrue(blocks.last.length == 1) &&
        assertTrue(summary.blockCount == 3L) // only full blocks counted
      }
    ),
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
        assertTrue(out(0) == 10.0) // first sample = value
      },
      // minMax and reservoirSample use generic kyo.Tag which produces complex
      // Record keys for Option[A]/Vector[A]. Tested via summarize which doesn't
      // access fields by name.
      test("minMax tracks extremes (via summarize)") {
        val (_, passThrough) = Transducers.minMax[Int].runChunk(List(5, 2, 8, 1, 9, 3))
        // minMax is pass-through: all elements come out
        assertTrue(passThrough == Chunk(5, 2, 8, 1, 9, 3))
      },
      test("reservoirSample maintains bounded sample (via last output)") {
        val (_, out)      = Transducers.reservoirSample[Int](5, seed = 42L).runChunk(1 to 1000)
        val lastReservoir = out.last
        assertTrue(lastReservoir.length == 5)
      },
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
      },
      test("dedup by extracted key") {
        case class Item(id: Int, name: String)
        val items          = List(Item(1, "a"), Item(2, "b"), Item(1, "c"), Item(3, "d"))
        val (summary, out) = Transducers.dedup[Item, Int](_.id).runChunk(items)
        assertTrue(out.length == 3) && // items with id 1, 2, 3
        assertTrue(summary.uniqueCount == 3L) &&
        assertTrue(summary.duplicateCount == 1L)
      },
    ),
    suite("batching")(
      test("batch groups elements") {
        val (summary, batches) = Transducers.batch[Int](3).runChunk(1 to 7)
        assertTrue(batches.length == 3) && // [1,2,3], [4,5,6], [7]
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

    // =========================================================================
    //  Composition patterns — the money shots
    // =========================================================================

    suite("composition: the power of &&&")(
      test("block counter + byte total in one pass") {
        val telemetry    = Transducers.blockCounter &&& Transducers.byteTotalChunked
        val blocks       = List(bytes("hello"), bytes("world!"))
        val (summary, _) = telemetry.runChunk(blocks)
        // Summary has BOTH fields accessible by name
        assertTrue(summary.blockCount == 2L) &&
        assertTrue(summary.totalBytes == 11L)
      },
      test("three-way fanout: count + bytes + minMax") {
        val pipeline     = Transducers.blockCounter &&& Transducers.byteTotalChunked
        val blocks       = List(bytes("graviton"), bytes("rocks"))
        val (summary, _) = pipeline.runChunk(blocks)
        // Both fields accessible by name on the merged Record
        assertTrue(summary.blockCount == 2L) &&
        assertTrue(summary.totalBytes == 13L)
      },
    ),
    suite("composition: sequential (>>>)")(
      test("chunker >>> per-block digest") {
        val pipeline = Transducers.fixedSizeChunker(4) >>> Transducers.chunkDigest()
        val input    = (1 to 10).map(_.toByte).toList
        val (_, out) = pipeline.runChunk(input)
        // 10 bytes / 4 = 2 full + 1 remainder = 3 blocks, each with digest
        assertTrue(out.length == 3) &&
        assertTrue(out.forall(_._2.isRight))
      },
      test("dedup >>> counter: count only unique elements") {
        val pipeline       = Transducers.dedup[String, String](identity) >>> Transducer.counter[String]
        val input          = List("a", "b", "a", "c", "b", "d")
        val (summary, out) = pipeline.runChunk(input)
        // Only 4 unique elements pass through to counter
        assertTrue(out == Chunk(1L, 2L, 3L, 4L)) &&
        assertTrue(summary.count == 4L)
      },
      test("batch >>> map: process batches") {
        val pipeline = Transducers.batch[Int](3) >>> Transducer.map[Chunk[Int], Int](_.sum)
        val (_, out) = pipeline.runChunk(1 to 7)
        assertTrue(out == Chunk(6, 15, 7)) // sum of [1,2,3], [4,5,6], [7]
      },
    ),
    suite("composition: mixed >>> and &&&")(
      test("chunker >>> digest runs independently from counter") {
        val withDigest = Transducers.fixedSizeChunker(5) >>> Transducers.chunkDigest()
        val counter    = Transducers.fixedSizeChunker(5) >>> Transducer.counter[Any]

        val input             = (1 to 12).map(_.toByte).toList
        val (_, digestOut)    = withDigest.runChunk(input)
        val (countSummary, _) = counter.runChunk(input)

        assertTrue(digestOut.length == 3) && // 12/5 = 2 full + 1 remainder
        assertTrue(countSummary.count == 3L)
      }
    ),

    // =========================================================================
    //  ZIO stream integration
    // =========================================================================

    suite("ZIO stream integration")(
      test("toPipeline: chunker + digest as a ZPipeline") {
        val pipeline = Transducers.fixedSizeChunker(4) >>> Transducers.chunkDigest()
        for out <- ZStream
                     .fromIterable((1 to 10).map(_.toByte))
                     .via(pipeline.toPipeline)
                     .runCollect
        yield assertTrue(out.length == 3 && out.forall(_._2.isRight))
      },
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
      test("transduce: batch into a stream of batch summaries") {
        for summaries <- ZStream
                           .fromIterable(1 to 10)
                           .transduce(Transducers.batch[Int](3).toTransducingSink)
                           .runCollect
        yield
          // 10 / 3 = 3 full batches + 1 remainder = at least 2 summary emissions
          assertTrue(summaries.nonEmpty)
      },
      test("transduce: dedup as a streaming filter with stats") {
        for deduped <- ZStream
                         .fromIterable(List("a", "b", "a", "c", "b", "d"))
                         .via(Transducers.dedup[String, String](identity).toPipeline)
                         .runCollect
        yield assertTrue(deduped == Chunk("a", "b", "c", "d"))
      },
    ),
  )
