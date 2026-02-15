package graviton.core.scan

import graviton.core.bytes.*
import graviton.core.keys.BinaryKey
import zio.*
import zio.test.*
import zio.test.Assertion.*

object CasIngestSpec extends ZIOSpecDefault:

  def spec = suite("CasIngest")(
    suite("blockKeyDeriver")(
      test("derives unique keys for different blocks") {
        val block1             = Chunk.fromArray(Array.fill(100)(1.toByte))
        val block2             = Chunk.fromArray(Array.fill(100)(2.toByte))
        val deriver            = CasIngest.blockKeyDeriver()
        val (summary, outputs) = deriver.runChunk(List(block1, block2))
        assertTrue(
          outputs.length == 2,
          outputs(0).key != outputs(1).key,
          outputs(0).size == 100,
          outputs(1).size == 100,
          summary.blocksKeyed == 2L,
        )
      },
      test("derives identical keys for identical blocks") {
        val block        = Chunk.fromArray(Array.fill(100)(42.toByte))
        val deriver      = CasIngest.blockKeyDeriver()
        val (_, outputs) = deriver.runChunk(List(block, block))
        assertTrue(
          outputs.length == 2,
          outputs(0).key == outputs(1).key,
        )
      },
      test("skips empty blocks") {
        val deriver            = CasIngest.blockKeyDeriver()
        val (summary, outputs) = deriver.runChunk(List(Chunk.empty))
        assertTrue(
          outputs.isEmpty,
          summary.blocksKeyed == 0L,
        )
      },
      test("key bits match block size") {
        val block        = Chunk.fromArray(Array.fill(512)(0xab.toByte))
        val deriver      = CasIngest.blockKeyDeriver()
        val (_, outputs) = deriver.runChunk(List(block))
        assertTrue(
          outputs.length == 1,
          outputs(0).key.bits.size == 512L,
          outputs(0).payload.length == 512,
        )
      },
    ),
    suite("pipeline (full CAS ingest)")(
      test("count + hash + rechunk + blockKey composes correctly") {
        val data              = Chunk.fromArray(Array.fill(2048)(0xff.toByte))
        // 2048 bytes with blockSize=1024 → 2 blocks
        val p                 = CasIngest.pipeline(blockSize = 1024)
        val (summary, blocks) = p.runChunk(List(data))
        assertTrue(
          blocks.length == 2,
          blocks(0).size == 1024,
          blocks(1).size == 1024,
          summary.totalBytes == 2048L,
          summary.blocksKeyed == 2L,
          summary.blockCount == 2L,
          summary.digestHex.nonEmpty,
        )
      },
      test("handles non-aligned data with remainder block") {
        val data              = Chunk.fromArray(Array.fill(1500)(0xcc.toByte))
        // 1500 bytes with blockSize=1024 → 1 full block + 1 remainder (476 bytes)
        val p                 = CasIngest.pipeline(blockSize = 1024)
        val (summary, blocks) = p.runChunk(List(data))
        assertTrue(
          blocks.length == 2,
          blocks(0).size == 1024,
          blocks(1).size == 476,
          summary.totalBytes == 1500L,
          summary.blocksKeyed == 2L,
        )
      },
      test("compiles to ZPipeline and processes stream") {
        val data = Chunk.fromArray(Array.fill(3072)(0xaa.toByte))
        val p    = CasIngest.pipeline(blockSize = 1024)
        for results <- zio.stream.ZStream
                         .fromChunk(data)
                         .rechunk(256)
                         .mapChunks(c => Chunk.single(c))
                         .via(p.toPipeline)
                         .runCollect
        yield assertTrue(
          results.length == 3,
          results.forall(_.size == 1024),
        )
      },
      test("compiles to ZSink and yields summary") {
        val data = Chunk.fromArray(Array.fill(2048)(0xbb.toByte))
        val p    = CasIngest.pipeline(blockSize = 1024)
        for (summary, blocks) <- zio.stream.ZStream
                                   .fromChunk(data)
                                   .rechunk(512)
                                   .mapChunks(c => Chunk.single(c))
                                   .run(p.toSink)
        yield assertTrue(
          blocks.length == 2,
          summary.totalBytes == 2048L,
          summary.digestHex.nonEmpty,
        )
      },
    ),
  )
