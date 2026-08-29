package graviton.contentlab

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.MaxLength
import zio.*
import zio.stream.ZStream

/** The only byte collector in the streamed browser analyzer. */
private[contentlab] object BoundedBrowserBytes:
  type PdfSignature = Chunk[Byte] :| MaxLength[5]

  /** Pull at most the five bytes required to recognize `%PDF-`. */
  def pdfSignature[R](source: ZStream[R, Throwable, Byte]): ZIO[R, Throwable, PdfSignature] =
    source
      .take(5L)
      .runCollect
      .map(_.refineUnsafe[MaxLength[5]])

end BoundedBrowserBytes
