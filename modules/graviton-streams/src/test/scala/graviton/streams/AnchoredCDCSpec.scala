package graviton.streams

import zio.*
import zio.Chunk
import zio.stream.ZStream
import zio.test.*

object AnchoredCDCSpec extends ZIOSpecDefault:

  private def ascii(value: String): Chunk[Byte] =
    Chunk.fromArray(value.getBytes("US-ASCII"))

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("AnchoredCDCSpec")(
      test("splits on configured anchors within window budgets") {
        val cfg     = AnchoredCDC.Config(
          minBytes = 4,
          avgBytes = 8,
          maxBytes = 32,
          anchors = Chunk(
            AnchoredCDC.Anchor(label = "end", bytes = ascii("END"), includePattern = true, scanBudgetBytes = Some(16))
          ),
          scanWindowBytes = 32,
        )
        val payload = ascii("AAAAENDbbbbENDcccc")
        for {
          blocks <- ZStream.fromChunk(payload).via(AnchoredCDC.chunker(cfg)).runCollect
        } yield assertTrue(
          blocks.length == 3,
          blocks.head.length == 7,        // AAAAEND
          blocks(1).length == 7,          // bbbbEND
          blocks(2).length == 4,
        )
      },
      test("forces chunk when anchors missing but threshold exceeded") {
        val cfg     = AnchoredCDC.Config(
          minBytes = 4,
          avgBytes = 8,
          maxBytes = 16,
          anchors = Chunk.empty,
          scanWindowBytes = 16,
          rechunkThreshold = 0.5,
        )
        val payload = Chunk.fromArray(Array.fill(40)(0x1.toByte))
        for {
          chunks <- ZStream.fromChunk(payload).via(AnchoredCDC.chunker(cfg)).runCollect
        } yield assertTrue(chunks.forall(_.length <= cfg.maxBytes))
      },
      test("pdf semantic anchors capture stream/object boundaries") {
        val snippet =
          """1 0 obj
            |<< /Length 5 >>
            |stream
            |hello
            |endstream
            |endobj
            |xref
            |0 2
            |0000000000 65535 f 
            |trailer
            |<< /Root 1 0 R >>
            |startxref
            |1234
            |%%EOF""".stripMargin
        val cfg     = AnchoredCDC.Pdf.semanticConfig
        val payload = ascii(snippet)
        for {
          chunks <- ZStream.fromChunk(payload).via(AnchoredCDC.chunker(cfg)).runCollect
        } yield assertTrue(chunks.exists(chunk => new String(chunk.toArray, "US-ASCII").contains("endstream")))
      },
    )
