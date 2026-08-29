package graviton.contentlab

import org.scalajs.dom
import scala.scalajs.js.typedarray.Uint8Array
import zio.*
import zio.pdf.PdfSource
import zio.stream.ZStream

/**
 * A reusable browser source that owns the Blob read size.
 *
 * `Blob.stream()` may choose implementation-defined chunk sizes. The content
 * lab instead requests explicit 64 KiB slices so its working-memory contract
 * includes the bytes crossing the browser boundary, not only downstream ZIO
 * buffers.
 */
private[contentlab] object BrowserBlobSource:
  final val ReadBytes = 64 * 1024

  def fromBlob(blob: dom.Blob, expectedSize: BrowserFileAnalysis.FileSize): PdfSource =
    new PdfSource:
      def bytes: ZStream[Any, Throwable, Byte] =
        ZStream
          .unfoldZIO(0L) { offset =>
            if offset >= expectedSize.value then ZIO.none
            else
              val end = math.min(expectedSize.value, offset + ReadBytes.toLong)
              ZIO
                .fromPromiseJS(blob.slice(offset.toDouble, end.toDouble).arrayBuffer())
                .map(buffer => Some((copyBytes(new Uint8Array(buffer)), end)))
          }
          .flattenChunks

  private def copyBytes(bytes: Uint8Array): Chunk[Byte] =
    val result = Array.ofDim[Byte](bytes.length)
    var index  = 0
    while index < bytes.length do
      result(index) = bytes(index).toByte
      index += 1
    Chunk.fromArray(result)

end BrowserBlobSource
