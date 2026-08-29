package graviton.pdflab

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.MaxLength
import zio.*
import zio.stream.ZStream

/** The only byte collector in the browser PDF editor. */
private[pdflab] object BoundedPdfOutput:
  final val MaximumBytes = 32 * 1024 * 1024
  type Bytes = Chunk[Byte] :| MaxLength[33554432]

  final case class TooLarge(maximum: Int) extends Exception(s"The transformed PDF exceeds the $maximum-byte browser editing limit.")

  /** Read one byte beyond the limit, then return only a compile-time bounded value. */
  def collect[R](source: ZStream[R, Throwable, Byte]): ZIO[R, Throwable, Bytes] =
    source
      .take(MaximumBytes.toLong + 1L)
      .runCollect
      .flatMap(bytes => ZIO.fromEither(bytes.refineEither[MaxLength[33554432]].left.map(_ => TooLarge(MaximumBytes))))

end BoundedPdfOutput
