package graviton.contentlab

import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.annotation.JSExportTopLevel
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import zio.*

object ContentLabExport:
  private val runtime = Runtime.default

  @JSExportTopLevel("analyzeGravitonFile")
  def analyzeFile(
    file: dom.File,
    strategyValue: String,
    targetBytes: Int,
    onBlock: js.Function1[js.Object, Unit],
  ): js.Promise[js.Object] =
    val effect = for
      strategy  <- ZIO.fromEither(BrowserFileAnalysis.Strategy.parse(strategyValue))
      config    <- ZIO.fromEither(BrowserFileAnalysis.Config.make(strategy, targetBytes))
      fileSize  <- ZIO.fromEither(BrowserFileAnalysis.fileSize(file.size))
      mediaType <- ZIO.fromEither(BrowserFileAnalysis.mediaType(file.`type`))
      source     = BrowserBlobSource.fromBlob(file, fileSize)
      result    <- BrowserFileAnalysis.analyze(
                     source,
                     fileSize,
                     mediaType,
                     config,
                     block => ZIO.attempt(onBlock(blockToJavaScript(block))).ignore,
                   )
    yield analysisToJavaScript(result)

    run(effect).toJSPromise

  private def run[A](effect: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(effect)
    }

  private def analysisToJavaScript(analysis: BrowserFileAnalysis.Analysis): js.Object =
    js.Dynamic.literal(
      byteCount = analysis.byteCount.value.toDouble,
      contentId = analysis.contentId,
      digestHex = analysis.digestHex,
      advertisedMediaType = analysis.advertisedMediaType.fullType,
      confirmedMediaType = analysis.confirmedMediaType.fullType,
      pdfSignature = analysis.pdfSignature,
      mediaTypeMismatch = analysis.mediaTypeMismatch,
      strategy = analysis.strategy.wireName,
      strategyLabel = analysis.strategy.label,
      targetBytes = analysis.targetBytes,
      maximumBlockBytes = analysis.maximumBlockBytes,
      maximumOwnedBytes = analysis.maximumOwnedBytes.toDouble,
      uniqueBlocks = analysis.uniqueBlocks,
      duplicateBlocks = analysis.duplicateBlocks,
      blocks = js.Array(analysis.blocks.map(blockToJavaScript)*),
      engine = "Graviton Scala.js + ZIO PDF + incremental SHA-256",
    )

  private def blockToJavaScript(block: BrowserFileAnalysis.BlockRange): js.Object =
    js.Dynamic.literal(
      index = block.index,
      start = block.start.toDouble,
      endExclusive = block.endExclusive.toDouble,
      length = block.length,
      digestHex = block.digestHex,
      contentId = block.contentId,
      cut = block.cut.wireName,
      duplicateWithinFile = block.duplicateWithinFile,
    )

end ContentLabExport
