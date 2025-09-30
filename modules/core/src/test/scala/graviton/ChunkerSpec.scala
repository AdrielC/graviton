package graviton

import graviton.chunking.*
import graviton.core.model.Block
import zio.*
import zio.stream.*
import zio.test.*
import java.util.zip.{Deflater, DeflaterOutputStream}
import java.io.ByteArrayOutputStream

object ChunkerSpec extends ZIOSpecDefault:

  private def compress(data: Array[Byte], level: Int): Array[Byte] =
    val bos  = new ByteArrayOutputStream()
    val defl = new Deflater(level)
    val dos  = new DeflaterOutputStream(bos, defl)
    dos.write(data)
    dos.finish()
    bos.toByteArray

  private def pdfWith(data: Array[Byte], level: Int): Chunk[Byte] =
    val comp   = compress(data, level)
    val dict   = s"<< /Length ${comp.length} /Filter /FlateDecode >>\n".getBytes(
      "ISO-8859-1"
    )
    val pre    = "%PDF-1.4\n1 0 obj\n".getBytes("ISO-8859-1")
    val stream = "stream\n".getBytes("ISO-8859-1")
    val end    = "\nendstream\nendobj\n".getBytes("ISO-8859-1")
    Chunk.fromArray(pre ++ dict ++ stream ++ comp ++ end)

  def spec = suite("ChunkerSpec")(
    test("fixed chunker splits by size") {
      val data = Chunk.fromArray("abcdef".getBytes("UTF-8"))
      ZStream.fromChunk(data).via(FixedChunker(2).pipeline).runCollect.map { out =>
        val expected = Chunk(
          Block.unsafeFromChunk(Chunk.fromArray("ab".getBytes)),
          Block.unsafeFromChunk(Chunk.fromArray("cd".getBytes)),
          Block.unsafeFromChunk(Chunk.fromArray("ef".getBytes)),
        )
        assertTrue(out.map(_.toChunk) == expected.map(_.toChunk))
      }
    },
    test("pdf chunker normalizes compressed streams") {
      val content = "hello pdf".getBytes("UTF-8")
      val pdf1    = pdfWith(content, Deflater.BEST_SPEED)
      val pdf2    = pdfWith(content, Deflater.BEST_COMPRESSION)
      for
        c1 <- ZStream.fromChunk(pdf1).via(PdfChunker.pipeline).runCollect
        c2 <- ZStream.fromChunk(pdf2).via(PdfChunker.pipeline).runCollect
      yield assertTrue(
        c1.map(_.toChunk) == c2.map(_.toChunk) && c1.exists(_.toChunk == Chunk.fromArray(content))
      )
    },
  )
