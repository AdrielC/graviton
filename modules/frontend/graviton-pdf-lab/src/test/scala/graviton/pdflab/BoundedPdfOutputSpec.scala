package graviton.pdflab

import _root_.scodec.bits.BitVector
import scala.scalajs.reflect.annotation.EnableReflectiveInstantiation
import zio.*
import zio.pdf.*
import zio.stream.ZStream
import zio.test.*
import zio.test.Assertion.*

@EnableReflectiveInstantiation
object BoundedPdfOutputSpec extends ZIOSpecDefault:
  def spec =
    suite("BoundedPdfOutput")(
      test("accepts a small encoded PDF stream") {
        val bytes = ZStream.fromIterable("%PDF-1.7".getBytes("UTF-8"))
        assertZIO(BoundedPdfOutput.collect(bytes).map(_.length))(equalTo(8))
      },
      test("rejects one byte beyond the declared output cap") {
        val mebibyte  = zio.Chunk.fromArray(Array.fill[Byte](1024 * 1024)(0))
        val oversized = ZStream
          .fromIterable(0 until 33)
          .flatMap(_ => ZStream.fromChunk(mebibyte))
          .take(BoundedPdfOutput.MaximumBytes.toLong + 1L)
        assertZIO(BoundedPdfOutput.collect(oversized).exit)(fails(isSubtype[BoundedPdfOutput.TooLarge](anything)))
      },
      test("returns an unchanged canonical PDF and a verified font variant") {
        for
          source <- compatibleFontPdf
          result <- BrowserPdfTools
                      .compareExistingFont(PdfSource.fromChunk(source), source.length.toLong, "SourceFace", "TargetFace")
                      .provide(PdfEngine.live)
        yield assertTrue(
          result.canonicalSource.nonEmpty,
          result.replacement.bytes.nonEmpty,
          result.canonicalSource != result.replacement.bytes,
          result.replacement.sourceObjectNumbers == Chunk(5L),
          result.replacement.targetObjectNumber == 6L,
          result.replacement.resourceBindingsRewritten == 1L,
        )
      },
    )

  private def compatibleFontPdf: Task[Chunk[Byte]] =
    def font(number: Long, baseFont: String): IndirectObj =
      IndirectObj.nostream(
        number,
        Prim.dict(
          "Type"      -> Prim.Name("Font"),
          "Subtype"   -> Prim.Name("Type1"),
          "BaseFont"  -> Prim.Name(baseFont),
          "Encoding"  -> Prim.Name("WinAnsiEncoding"),
          "FirstChar" -> Prim.Number(BigDecimal(32)),
          "LastChar"  -> Prim.Number(BigDecimal(33)),
          "Widths"    -> Prim.Array(Prim.Number(BigDecimal(500)), Prim.Number(BigDecimal(500))),
        ),
      )

    val catalog = IndirectObj.nostream(
      1,
      Prim.dict("Type" -> Prim.Name("Catalog"), "Pages" -> Prim.Ref(2, 0)),
    )
    val pages   = IndirectObj.nostream(
      2,
      Prim.dict(
        "Type"  -> Prim.Name("Pages"),
        "Kids"  -> Prim.Array(Prim.Ref(3, 0)),
        "Count" -> Prim.Number(BigDecimal(1)),
      ),
    )
    val page    = IndirectObj.nostream(
      3,
      Prim.dict(
        "Type"      -> Prim.Name("Page"),
        "Parent"    -> Prim.Ref(2, 0),
        "MediaBox"  -> Prim.Array.nums(0, 0, 612, 792),
        "Resources" -> Prim.dict("Font" -> Prim.dict("F1" -> Prim.Ref(5, 0), "F2" -> Prim.Ref(6, 0))),
        "Contents"  -> Prim.Ref(4, 0),
      ),
    )
    val content = IndirectObj.stream(
      4,
      Prim.Dict.empty,
      BitVector("BT /F1 12 Tf (AB) Tj ET\n".getBytes("UTF-8")),
    )
    val trailer = Trailer(BigDecimal(7), Prim.dict("Root" -> Prim.Ref(1, 0)), Some(Prim.Ref(1, 0)))

    ZStream(catalog, pages, page, content, font(5, "SourceFace"), font(6, "TargetFace"))
      .via(WritePdf.objects(trailer))
      .runFold(Chunk.empty[Byte])((all, next) => all ++ Chunk.fromArray(next.toArray))

end BoundedPdfOutputSpec
