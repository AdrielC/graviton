package graviton.pdflab

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import zio.*
import zio.pdf.{ByteLimit, PdfEngine, PdfInspection, PdfSource, PdfTransform}

object BrowserPdfTools:
  final val MaximumEditablePdfBytes = BoundedPdfOutput.MaximumBytes
  type EditablePdfOutput = BoundedPdfOutput.Bytes

  object EditablePdfSize extends RefinedSubtype[Long, GreaterEqual[1L] & LessEqual[33554432L]]

  final case class Font(objectNumber: Long, baseFont: String, subtype: Option[String], remapCandidate: Boolean)
  final case class Inventory(fonts: Chunk[Font])
  final case class Replacement(
    bytes: EditablePdfOutput,
    sourceBaseFont: String,
    targetBaseFont: String,
    sourceObjectNumbers: Chunk[Long],
    targetObjectNumber: Long,
    resourceBindingsRewritten: Long,
  )
  final case class VariantComparison(canonicalSource: EditablePdfOutput, replacement: Replacement)

  sealed abstract class Error(message: String) extends Exception(message)
  object Error:
    final case class NotEditableSize(actual: Long, maximum: Int)
        extends Error(s"Browser PDF editing is limited to $maximum bytes; this file is $actual bytes.")
    final case class Inspection(detail: String)   extends Error(s"ZIO PDF could not inspect this document: $detail")
    final case class Transform(detail: String)    extends Error(detail)
    final case class OutputTooLarge(maximum: Int) extends Error(s"The transformed PDF exceeds the $maximum-byte browser editing limit.")

  def inspect(source: PdfSource, fileSize: Long): ZIO[PdfEngine, Error, Inventory] =
    PdfInspection
      .run(PdfEngine.elements(source.bytes, options(fileSize)), PdfInspection.fontInventory)
      .mapError(error => Error.Inspection(message(error)))
      .map {
        case PdfInspection.Outcome.Accepted(report)    => inventory(report)
        case PdfInspection.Outcome.Rejected(report, _) => inventory(report)
      }

  def replaceExistingFont(
    source: PdfSource,
    fileSize: Long,
    fromBaseFont: String,
    toBaseFont: String,
  ): ZIO[PdfEngine, Error, Replacement] =
    for
      _       <- ZIO.fromEither(
                   EditablePdfSize
                     .either(fileSize)
                     .left
                     .map(_ => Error.NotEditableSize(fileSize, MaximumEditablePdfBytes))
                 )
      output  <- PdfTransform.fonts
                   .replaceExisting(fromBaseFont, toBaseFont)
                   .run(source.bytes, options(fileSize))
                   .mapError(error => Error.Transform(message(error)))
      bounded <- BoundedPdfOutput.collect(output.bytes).mapError {
                   case _: BoundedPdfOutput.TooLarge => Error.OutputTooLarge(MaximumEditablePdfBytes)
                   case error                        => Error.Transform(message(error))
                 }
      report   = output.value
    yield Replacement(
      bytes = bounded,
      sourceBaseFont = report.sourceBaseFont,
      targetBaseFont = report.targetBaseFont,
      sourceObjectNumbers = report.sourceObjectNumbers,
      targetObjectNumber = report.targetObjectNumber,
      resourceBindingsRewritten = report.resourceBindingsRewritten,
    )

  /**
   * Render an unchanged canonical baseline and an edited variant through the
   * same ZIO PDF encoder. Exact CAS reuse between these two outputs therefore
   * measures the local font edit instead of unrelated source-serialization
   * differences.
   */
  def compareExistingFont(
    source: PdfSource,
    fileSize: Long,
    fromBaseFont: String,
    toBaseFont: String,
  ): ZIO[PdfEngine, Error, VariantComparison] =
    for
      _         <- validateEditableSize(fileSize)
      canonical <- PdfTransform.text
                     .tokenize(PdfTransform.text.Tokenizer.from[Unit](_ => Chunk.empty))
                     .run(source.bytes, options(fileSize))
                     .mapError(error => Error.Transform(message(error)))
      baseline  <- collectBounded(canonical.bytes)
      edited    <- replaceExistingFont(source, fileSize, fromBaseFont, toBaseFont)
    yield VariantComparison(baseline, edited)

  private def validateEditableSize(fileSize: Long): IO[Error, Unit] =
    ZIO
      .fromEither(
        EditablePdfSize
          .either(fileSize)
          .left
          .map(_ => Error.NotEditableSize(fileSize, MaximumEditablePdfBytes))
      )
      .unit

  private def collectBounded(bytes: zio.stream.ZStream[Any, Throwable, Byte]): IO[Error, EditablePdfOutput] =
    BoundedPdfOutput.collect(bytes).mapError {
      case _: BoundedPdfOutput.TooLarge => Error.OutputTooLarge(MaximumEditablePdfBytes)
      case error                        => Error.Transform(message(error))
    }

  private def inventory(report: PdfInspection.Report): Inventory =
    Inventory(
      report.fonts.map(font => Font(font.objectNumber, font.baseFont, font.subtype, font.isExistingResourceRemapCandidate))
    )

  private def options(fileSize: Long): PdfEngine.Options =
    PdfEngine.Options(
      batchSize = 256 * 1024,
      maxInputBytes = math.max(1L, fileSize),
      maxMaterializedDocumentBytes = ByteLimit.mebibytes(32),
    )

  private def message(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)

end BrowserPdfTools
