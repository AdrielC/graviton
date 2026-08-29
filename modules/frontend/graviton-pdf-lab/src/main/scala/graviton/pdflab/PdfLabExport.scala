package graviton.pdflab

import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.annotation.JSExportTopLevel
import scala.scalajs.js.typedarray.Uint8Array
import zio.*
import zio.pdf.{PdfEngine, PdfSource}

object PdfLabExport:
  private val runtime = Runtime.default

  @JSExportTopLevel("inspectGravitonPdf")
  def inspectPdf(file: dom.File): js.Promise[js.Object] =
    val effect = for
      fileSize <- ZIO.fromEither(safeFileSize(file.size))
      result   <- BrowserPdfTools.inspect(PdfSource.fromBlob(file), fileSize)
    yield js.Dynamic.literal(
      fonts = js.Array(result.fonts.map(fontToJavaScript)*),
      engine = "zio-pdf 0.2.0-RC7 Scala.js",
    )

    run(effect).toJSPromise

  @JSExportTopLevel("remapGravitonPdfFont")
  def remapPdfFont(file: dom.File, fromBaseFont: String, toBaseFont: String): js.Promise[js.Object] =
    val effect = for
      fileSize   <- ZIO.fromEither(safeFileSize(file.size))
      result     <- BrowserPdfTools.compareExistingFont(
                      PdfSource.fromBlob(file),
                      fileSize,
                      fromBaseFont,
                      toBaseFont,
                    )
      replacement = result.replacement
    yield js.Dynamic.literal(
      canonicalBytes = toUint8Array(result.canonicalSource),
      bytes = toUint8Array(replacement.bytes),
      sourceBaseFont = replacement.sourceBaseFont,
      targetBaseFont = replacement.targetBaseFont,
      sourceObjectNumbers = js.Array(replacement.sourceObjectNumbers.map(_.toDouble)*),
      targetObjectNumber = replacement.targetObjectNumber.toDouble,
      resourceBindingsRewritten = replacement.resourceBindingsRewritten.toDouble,
      pdfDelta = structuralDeltaToJavaScript(result.structuralDelta),
      engine = "zio-pdf 0.2.0-RC7 Scala.js",
    )

    run(effect).toJSPromise

  private def run[A](effect: ZIO[PdfEngine, Throwable, A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(effect.provideLayer(PdfEngine.live))
    }

  private def safeFileSize(value: Double): Either[Throwable, Long] =
    if value.isNaN || value.isInfinity || value < 0.0 || value > 9007199254740991.0 || value != math.floor(value) then
      Left(new IllegalArgumentException(s"The browser reported an invalid file size: $value."))
    else Right(value.toLong)

  private def fontToJavaScript(font: BrowserPdfTools.Font): js.Object =
    js.Dynamic.literal(
      objectNumber = font.objectNumber.toDouble,
      baseFont = font.baseFont,
      subtype = font.subtype.orNull,
      remapCandidate = font.remapCandidate,
    )

  private def structuralDeltaToJavaScript(delta: BrowserPdfTools.StructuralDelta): js.Object =
    js.Dynamic.literal(
      valid = true,
      windows = delta.windows.toDouble,
      windowSize = BrowserPdfTools.PdfDiffWindowSize,
      unchanged = delta.same.toDouble,
      changed = delta.changed.toDouble,
      added = delta.added.toDouble,
      removed = delta.removed.toDouble,
      streamPayloadsChanged = delta.streamPayloadsChanged.toDouble,
      reportedChangeLimit = BrowserPdfTools.MaximumReportedPdfChanges,
      changes = js.Array(delta.samples.map(structuralChangeToJavaScript)*),
    )

  private def structuralChangeToJavaScript(change: BrowserPdfTools.StructuralChange): js.Object =
    js.Dynamic.literal(
      kind = change.kind.toString.toLowerCase,
      leftObjectNumber = change.leftObjectNumber.fold[js.Any](null)(_.toDouble),
      rightObjectNumber = change.rightObjectNumber.fold[js.Any](null)(_.toDouble),
      componentKind = change.componentKind.toString.toLowerCase,
      payloadChanged = change.payloadChanged,
    )

  private def toUint8Array(bytes: Chunk[Byte]): Uint8Array =
    val result = new Uint8Array(bytes.length)
    var index  = 0
    while index < bytes.length do
      result(index) = (bytes(index).toInt & 0xff).toShort
      index += 1
    result

end PdfLabExport
