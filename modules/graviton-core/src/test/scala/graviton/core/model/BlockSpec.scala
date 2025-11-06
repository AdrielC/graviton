package graviton.core.model

import zio.Chunk
import zio.test.*

object BlockSpec extends ZIOSpecDefault:

  private val sampleBytes: Chunk[Byte] = Chunk.fromIterable(0 until 1024).map(_.toByte)

  override def spec: Spec[TestEnvironment, Any] =
    suite("Block")(
      test("fromChunk accepts chunks within bounds") {
        val result = Block.fromChunk(sampleBytes)
        assertTrue(result.isRight && result.exists(_.length == sampleBytes.length))
      },
      test("fromChunk rejects oversized chunks") {
        val oversized = Chunk.fill(ByteConstraints.MaxBlockBytes + 1)(0.toByte)
        val result    = Block.fromChunk(oversized)
        assertTrue(result.isLeft)
      },
      test("chunkify splits large payloads into bounded blocks") {
        val payload = Chunk.fill(ByteConstraints.MaxBlockBytes * 2 + 100)(0.toByte)
        val chunked = BlockBuilder.chunkify(payload)
        val lengths = chunked.map(_.length)
        assertTrue(chunked.length == 3 && lengths.forall(_ <= ByteConstraints.MaxBlockBytes))
      },
    )
