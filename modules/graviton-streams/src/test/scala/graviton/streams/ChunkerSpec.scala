package graviton.streams

import graviton.core.model.Block
import graviton.core.model.Block.*
import graviton.core.types.UploadChunkSize
import zio.Chunk
import zio.stream.ZStream
import zio.test.*

object ChunkerSpec extends ZIOSpecDefault:

  private def bytes(n: Int): Chunk[Byte] =
    Chunk.fromArray((0 until n).map(_.toByte).toArray)

  private def ascii(s: String): Chunk[Byte] =
    Chunk.fromArray(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII))

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
      test("delimiter chunker can include delimiter bytes") {
        val chunker = Chunker.delimiter(ascii("\n"), includeDelimiter = true, minBytes = 1, maxBytes = 16)
        assertZIO(
          ZStream.fromChunk(ascii("a\nbb\nccc")).via(chunker.pipeline).runCollect.map(_.map(_.bytes))
        )(Assertion.equalTo(Chunk(ascii("a\n"), ascii("bb\n"), ascii("ccc"))))
      },
      test("delimiter chunker can exclude delimiter bytes (dropping delimiter)") {
        val chunker = Chunker.delimiter(ascii("\n"), includeDelimiter = false, minBytes = 1, maxBytes = 16)
        assertZIO(
          ZStream.fromChunk(ascii("a\nbb\nccc")).via(chunker.pipeline).runCollect.map(_.map(_.bytes))
        )(Assertion.equalTo(Chunk(ascii("a"), ascii("bb"), ascii("ccc"))))
      },
      test("delimiter chunker does not emit empty blocks when input begins with delimiter (exclude mode)") {
        val chunker = Chunker.delimiter(ascii("\n"), includeDelimiter = false, minBytes = 1, maxBytes = 16)
        assertZIO(
          ZStream.fromChunk(ascii("\n\nx\n")).via(chunker.pipeline).runCollect.map(_.map(_.bytes))
        )(Assertion.equalTo(Chunk(ascii("x"))))
      },
      test("fastcdc is invariant to upstream chunk boundaries and respects maxBytes") {
        val data    = bytes(10_000)
        val splits  = Chunk(1, 7, 64, 3, 1024)
        val chunker = Chunker.fastCdc(min = 256, avg = 1024, max = 2048)

        def splitStream(in: Chunk[Byte]): ZStream[Any, Nothing, Byte] =
          val parts = zio.ChunkBuilder.make[Chunk[Byte]]()
          var idx   = 0
          var si    = 0
          while idx < in.length do
            val n = math.max(1, splits(si % splits.length))
            parts += in.slice(idx, math.min(in.length, idx + n))
            idx += n
            si += 1
          ZStream.fromChunks(parts.result()*)

        for
          a <- ZStream.fromChunk(data).via(chunker.pipeline).runCollect.map(_.map(_.bytes))
          b <- splitStream(data).via(chunker.pipeline).runCollect.map(_.map(_.bytes))
        yield assertTrue(
          a == b,
          a.forall(b => b.length >= 1 && b.length <= 2048),
        )
      },
      test("core state machine can run on a single Chunk[Byte]") {
        val input = ascii("a\nbb\nccc")
        val st0   = ChunkerCore.init(ChunkerCore.Mode.Delimiter(ascii("\n"), includeDelimiter = true, minBytes = 1, maxBytes = 16)).toOption.get

        val (st1, out1) = st0.step(input).toOption.get
        val (_, out2)   = st1.finish.toOption.get

        assertTrue((out1 ++ out2).map(_.bytes) == Chunk(ascii("a\n"), ascii("bb\n"), ascii("ccc")))
      },
      test("chunker output type enforces non-empty blocks") {
        val empty: Chunk[Byte] = Chunk.empty
        val res                = Block.fromChunk(empty)
        assertTrue(res.isLeft)
      },
    )
