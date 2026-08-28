package graviton.pdflab

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import zio.*
import zio.pdf.{ByteLimit, CompareError, PdfDiff, PdfEngine, PdfInspection, PdfSource, PdfTransform}
import zio.stream.ZStream

object BrowserPdfTools:
  final val MaximumEditablePdfBytes   = BoundedPdfOutput.MaximumBytes
  final val MaximumReportedPdfChanges = 12
  final val PdfDiffWindowSize         = 128
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
  enum ChangeKind:
    case Changed, Added, Removed
  enum ComponentKind:
    case Object, Stream, Document
  final case class StructuralChange(
    kind: ChangeKind,
    leftObjectNumber: Option[Long],
    rightObjectNumber: Option[Long],
    componentKind: ComponentKind,
    payloadChanged: Boolean,
  )
  final case class StructuralDelta(
    windows: Long,
    same: Long,
    changed: Long,
    added: Long,
    removed: Long,
    streamPayloadsChanged: Long,
    samples: Chunk[StructuralChange],
  ):
    def edits: Long = changed + added + removed
  final case class VariantComparison(
    canonicalSource: EditablePdfOutput,
    replacement: Replacement,
    structuralDelta: StructuralDelta,
  )

  sealed abstract class Error(message: String) extends Exception(message)
  object Error:
    final case class NotEditableSize(actual: Long, maximum: Int)
        extends Error(s"Browser PDF editing is limited to $maximum bytes; this file is $actual bytes.")
    final case class Inspection(detail: String)           extends Error(s"ZIO PDF could not inspect this document: $detail")
    final case class Transform(detail: String)            extends Error(detail)
    final case class StructuralValidation(detail: String) extends Error(s"The transformed PDF failed structural validation: $detail")
    final case class OutputTooLarge(maximum: Int)         extends Error(s"The transformed PDF exceeds the $maximum-byte browser editing limit.")

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
      delta     <- compareVariants(baseline, edited.bytes)
    yield VariantComparison(baseline, edited, delta)

  private def compareVariants(
    canonical: EditablePdfOutput,
    edited: EditablePdfOutput,
  ): ZIO[PdfEngine, Error, StructuralDelta] =
    val maximumInput = math.max(canonical.length.toLong, edited.length.toLong)
    val opts         = options(maximumInput)
    val canonicalPdf = ZStream.fromChunk(canonical)
    val editedPdf    = ZStream.fromChunk(edited)

    for
      validation <- PdfEngine
                      .compare(canonicalPdf, editedPdf, opts)
                      .mapError(error => Error.StructuralValidation(message(error)))
      _          <- validation.fold(
                      errors => ZIO.fail(Error.StructuralValidation(errors.map(CompareError.format).mkString("; "))),
                      _ => ZIO.unit,
                    )
      delta      <- PdfEngine
                      .diff(
                        ZStream.fromChunk(canonical),
                        ZStream.fromChunk(edited),
                        PdfDiff.Config(
                          windowSize = PdfDiffWindowSize,
                          verifyRawStreamPayloads = true,
                        ),
                        opts,
                      )
                      .runFold(emptyDelta)(accumulateDelta)
                      .mapError(error => Error.StructuralValidation(message(error)))
    yield delta

  private val emptyDelta = StructuralDelta(0L, 0L, 0L, 0L, 0L, 0L, Chunk.empty)

  private def accumulateDelta(delta: StructuralDelta, window: PdfDiff.Window): StructuralDelta =
    window.edits.foldLeft(delta.copy(windows = delta.windows + 1L)) {
      case (current, _: PdfDiff.Edit.Same)                                 => current.copy(same = current.same + 1L)
      case (current, PdfDiff.Edit.Changed(left, right, _, payloadChanged)) =>
        val change = StructuralChange(
          ChangeKind.Changed,
          objectNumber(left),
          objectNumber(right),
          componentKind(right),
          payloadChanged,
        )
        current.copy(
          changed = current.changed + 1L,
          streamPayloadsChanged = current.streamPayloadsChanged + (if payloadChanged then 1L else 0L),
          samples = appendSample(current.samples, change),
        )
      case (current, PdfDiff.Edit.Added(right))                            =>
        current.copy(
          added = current.added + 1L,
          samples = appendSample(
            current.samples,
            StructuralChange(ChangeKind.Added, None, objectNumber(right), componentKind(right), false),
          ),
        )
      case (current, PdfDiff.Edit.Removed(left))                           =>
        current.copy(
          removed = current.removed + 1L,
          samples = appendSample(
            current.samples,
            StructuralChange(ChangeKind.Removed, objectNumber(left), None, componentKind(left), false),
          ),
        )
    }

  private[pdflab] def appendSample(samples: Chunk[StructuralChange], change: StructuralChange): Chunk[StructuralChange] =
    if samples.length < MaximumReportedPdfChanges then samples :+ change else samples

  private def objectNumber(component: PdfDiff.Component): Option[Long] =
    component.location match
      case PdfDiff.Location.Object(ref) => Some(ref.number)
      case PdfDiff.Location.Document    => None

  private def componentKind(component: PdfDiff.Component): ComponentKind =
    component.value match
      case PdfDiff.Value.Primitive(_)      => ComponentKind.Object
      case PdfDiff.Value.Stream(_, _)      => ComponentKind.Stream
      case PdfDiff.Value.Metadata(_, _, _) => ComponentKind.Document

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
