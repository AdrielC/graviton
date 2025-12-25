package graviton.streams

import graviton.core.model.Block
import graviton.core.types.UploadChunkSize
import zio.Chunk
import zio.stream.ZStream
import zio.test.*

object ChunkerSpec extends ZIOSpecDefault:

  private def bytes(n: Int): Chunk[Byte] =
    Chunk.fromArray((0 until n).map(_.toByte).toArray)

  override def spec: Spec[TestEnvironment, Any] =
    suite("Chunker")(
      test("fixed chunker does not emit empty blocks at exact multiples") {
        val chunker = Chunker.fixed(UploadChunkSize.unsafe(3))
        assertZIO(
          ZStream.fromChunk(bytes(6)).via(chunker.pipeline).runCollect.map(_.map(_.length))
        )(Assertion.equalTo(Chunk(3, 3)))
      },
      test("fixed chunker emits a final remainder block (non-empty)") {
        val chunker = Chunker.fixed(UploadChunkSize.unsafe(4))
        assertZIO(
          ZStream.fromChunk(bytes(10)).via(chunker.pipeline).runCollect.map(_.map(_.length))
        )(Assertion.equalTo(Chunk(4, 4, 2)))
      },
      test("fixed chunker emits no blocks for empty input") {
        val chunker = Chunker.fixed(UploadChunkSize.unsafe(4))
        assertZIO(
          ZStream.empty.via(chunker.pipeline).runCollect
        )(Assertion.isEmpty)
      },
      test("chunker output type enforces non-empty blocks") {
        val empty: Chunk[Byte] = Chunk.empty
        val res                = Block.fromChunk(empty)
        assertTrue(res.isLeft)
      },
    )
