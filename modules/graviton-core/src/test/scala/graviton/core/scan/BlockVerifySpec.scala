package graviton.core.scan

import graviton.core.bytes.*
import graviton.core.keys.{BinaryKey, KeyBits}
import zio.*
import zio.test.*

object BlockVerifySpec extends ZIOSpecDefault:

  /** Helper: hash a block and derive its BinaryKey.Block. */
  private def keyForBlock(block: Chunk[Byte]): BinaryKey.Block =
    val result = for
      hasher <- Hasher.hasher(HashAlgo.runtimeDefault, None)
      _       = hasher.update(block.toArray)
      digest <- hasher.digest
      bits   <- KeyBits.create(hasher.algo, digest, block.length.toLong)
      key    <- BinaryKey.block(bits)
    yield key
    result.toOption.get

  def spec = suite("BlockVerify")(
    suite("verifier")(
      test("passes when blocks match expected keys") {
        val block1             = Chunk.fromArray(Array.fill(100)(1.toByte))
        val block2             = Chunk.fromArray(Array.fill(100)(2.toByte))
        val keys               = IndexedSeq(keyForBlock(block1), keyForBlock(block2))
        val v                  = BlockVerify.verifier(keys)
        val (summary, results) = v.runChunk(List(block1, block2))
        assertTrue(
          summary.verified == 2L,
          summary.failed == 0L,
          summary.errors == 0L,
          results.length == 2,
          results.forall(_.isInstanceOf[BlockVerify.VerifyResult.Passed]),
        )
      },
      test("detects tampered blocks") {
        val block1             = Chunk.fromArray(Array.fill(100)(1.toByte))
        val tampered           = Chunk.fromArray(Array.fill(100)(99.toByte))
        val keys               = IndexedSeq(keyForBlock(block1))
        val v                  = BlockVerify.verifier(keys)
        val (summary, results) = v.runChunk(List(tampered))
        assertTrue(
          summary.verified == 0L,
          summary.failed == 1L,
          results.head.isInstanceOf[BlockVerify.VerifyResult.Failed],
        )
      },
      test("reports error when more blocks than expected") {
        val block              = Chunk.fromArray(Array.fill(50)(1.toByte))
        val keys               = IndexedSeq.empty[BinaryKey.Block]
        val v                  = BlockVerify.verifier(keys)
        val (summary, results) = v.runChunk(List(block))
        assertTrue(
          summary.errors == 1L,
          results.head.isInstanceOf[BlockVerify.VerifyResult.Error],
        )
      },
    ),
    suite("blobVerifier")(
      test("rechunk + verify end-to-end") {
        // Create 2048 bytes of data, rechunk to 1024-byte blocks, verify
        val data   = Chunk.fromArray(Array.fill(2048)(0xab.toByte))
        val block1 = Chunk.fromArray(Array.fill(1024)(0xab.toByte))
        val block2 = Chunk.fromArray(Array.fill(1024)(0xab.toByte))
        val keys   = IndexedSeq(keyForBlock(block1), keyForBlock(block2))

        val v                  = BlockVerify.blobVerifier(blockSize = 1024, expectedKeys = keys)
        val (summary, results) = v.runChunk(List(data))
        assertTrue(
          summary.verified == 2L,
          summary.failed == 0L,
          summary.blockCount == 2L,
          results.length == 2,
        )
      }
    ),
    suite("round-trip with CasIngest")(
      test("ingest then verify: all blocks pass") {
        val data = Chunk.fromArray(Array.fill(3072)(0xcc.toByte))

        // Ingest: get the keyed blocks
        val ingest           = CasIngest.pipeline(blockSize = 1024)
        val (_, keyedBlocks) = ingest.runChunk(List(data))

        // Extract keys in order
        val keys = keyedBlocks.map(_.key).toArray.toIndexedSeq

        // Verify: re-process the same data
        val verify                         = BlockVerify.blobVerifier(blockSize = 1024, expectedKeys = keys)
        val (verifySummary, verifyResults) = verify.runChunk(List(data))

        assertTrue(
          keyedBlocks.length == 3,
          verifySummary.verified == 3L,
          verifySummary.failed == 0L,
          verifySummary.errors == 0L,
          verifyResults.length == 3,
        )
      }
    ),
  )
