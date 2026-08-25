package graviton.shared.cas

import graviton.shared.cas.ContentAddressing.*
import zio.Chunk

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.annotation.JSExportTopLevel
import scala.scalajs.js.typedarray.Uint8Array
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object ContentLabExport:
  @JSExportTopLevel("analyzeGravitonContent")
  def analyzeUtf8(value: String, blockSize: Int): js.Promise[js.Object] =
    encodeUtf8(value)
      .fold(
        Future.failed,
        bytes => ContentAddressing.analyzeFuture(bytes, blockSize),
      )
      .map(toJavaScript)
      .toJSPromise

  private def encodeUtf8(value: String): Either[ContentAddressingError, InteractiveBytes] =
    if value.length > MaxTextCodeUnits then
      Left(
        ContentAddressingError.TextTooLarge(
          value.length,
          MaxTextCodeUnits,
        )
      )
    else
      try
        val textEncoder = js.Dynamic.newInstance(js.Dynamic.global.TextEncoder)()
        val encoded     = textEncoder.encode(value).asInstanceOf[Uint8Array]
        val bytes       = Chunk.fromIterable((0 until encoded.length).map(index => encoded(index).toByte))
        InteractiveBytes.fromChunk(bytes)
      catch case _: Throwable => Left(ContentAddressingError.TextEncodingUnavailable())

  private def toJavaScript(analysis: Analysis): js.Object =
    val blocks = analysis.blocks.map { block =>
      js.Dynamic.literal(
        index = block.index,
        offset = block.offset.value.toDouble,
        size = block.size.value.toDouble,
        contentId = block.contentId.render,
        duplicate = block.duplicate,
      )
    }

    js.Dynamic.literal(
      byteCount = analysis.byteCount.value.toDouble,
      blobId = analysis.blobId.render,
      blocks = js.Array(blocks*),
      uniqueCount = analysis.uniqueCount,
      duplicateCount = analysis.duplicateCount,
      engine = "graviton-shared Scala.js + Web Crypto",
    )
