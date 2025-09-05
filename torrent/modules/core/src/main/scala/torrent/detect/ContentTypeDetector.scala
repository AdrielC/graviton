package torrent.detect

import java.io.FileNotFoundException
import java.nio.file.{ Files, Path, Paths }

import torrent.utils.SecurityUtils

import zio.*
import zio.stream.*

trait ContentTypeDetector {
  def detectFromStream[R](
    stream: ZStream[R, Throwable, Byte]
  ): ZIO[R & Scope, Throwable, (Option[DetectionResult], ZStream[R, Throwable, Byte])]
  def detectFromBytes(bytes: ContentTypeDetector.ValidatedBytes): UIO[Option[DetectionResult]]
  def detectFromFile(path:   String): ZIO[Scope, Throwable, (Option[DetectionResult], ZStream[Scope, Throwable, Byte])]
}

object ContentTypeDetector {

  inline val MAX_BYTES_DETECT    = 8192
  inline val MAX_VALIDATED_BYTES = 1000

  opaque type ValidatedBytes = Chunk[Byte]

  object ValidatedBytes {
    def apply(chunk: Chunk[Byte]): Either[String, ValidatedBytes] =
      if (chunk.size > MAX_VALIDATED_BYTES) Left(s"Chunk size ${chunk.size} exceeds maximum of $MAX_VALIDATED_BYTES")
      else Right(chunk)

    private[ContentTypeDetector] def unsafe(chunk: Chunk[Byte]): ValidatedBytes = chunk

    extension (vb: ValidatedBytes) {
      def startsWith(prefix: Chunk[Byte]): Boolean = vb.startsWith(prefix)
      def underlying: Chunk[Byte]                  = vb
      def size: Int                                = vb.size
    }
  }

  val live: ZLayer[Any, Nothing, ContentTypeDetector] = ZLayer.succeed(
    new ContentTypeDetector {
      import ContentTypeDetector.ValidatedBytes.*

      override def detectFromBytes(bytes: ValidatedBytes): UIO[Option[DetectionResult]] = ZIO.succeed {
        if (bytes.startsWith(MagicBytes.PNG_HEADER))
          Some(DetectionResult(ContentType.PNG, ConfidenceScore.High))
        else if (bytes.startsWith(MagicBytes.PDF_HEADER))
          Some(DetectionResult(ContentType.PDF, ConfidenceScore.High))
        else if (bytes.startsWith(MagicBytes.RTF_HEADER))
          Some(DetectionResult(ContentType.RTF, ConfidenceScore.Medium))
        else if (bytes.startsWith(MagicBytes.TIF_HEADER))
          Some(DetectionResult(ContentType.TIF, ConfidenceScore.Medium))
        else if (bytes.startsWith(MagicBytes.DOCX_ZIP_SIG))
          MagicBytes.detectOfficeTypeFromZip(bytes).map(t => DetectionResult(t, ConfidenceScore.Medium))
        else if (MagicBytes.isCSV(bytes))
          Some(DetectionResult(ContentType.CSV, ConfidenceScore.Low))
        else if (MagicBytes.looksLikeJson(bytes))
          Some(DetectionResult(ContentType.JSON, ConfidenceScore.Low))
        else if (MagicBytes.isText(bytes.underlying))
          Some(DetectionResult(ContentType.PlainText, ConfidenceScore.Low))
        else None
      }

      override def detectFromStream[R](
        stream: ZStream[R, Throwable, Byte]
      ): ZIO[R & Scope, Throwable, (Option[DetectionResult], ZStream[R, Throwable, Byte])] =
        stream.peel(ZSink.take[Byte](MAX_BYTES_DETECT)).flatMap { case (header, rest) =>
          val validated = ValidatedBytes.unsafe(header)
          val restream  = ZStream.fromChunk(header) ++ rest
          detectFromBytes(validated).map((_, restream))
        }

      override def detectFromFile(
        path: String
      ): ZIO[Scope, Throwable, (Option[DetectionResult], ZStream[Scope, Throwable, Byte])] =
        for {
          filePath   <- ZIO.fromEither(
                          SecurityUtils
                            .validatePathV(path)
                            .toEither
                            .left
                            .map(errs => new IllegalArgumentException(errs.mkString(", ")))
                        )
          isReadable <- ZIO.attemptBlocking(Files.isReadable(filePath.value))
          _          <- ZIO.fail(new FileNotFoundException(s"File not readable: $path")).unless(isReadable)
          stream      = ZStream.fromFile(filePath.value.toFile)
          result     <- detectFromStream(stream)
        } yield result
    }
  )

  // --- Accessors ---
  def detectFromStream[R](
    stream: ZStream[R, Throwable, Byte]
  ): ZIO[ContentTypeDetector & R & Scope, Throwable, (Option[DetectionResult], ZStream[R, Throwable, Byte])] =
    ZIO.serviceWithZIO[ContentTypeDetector](_.detectFromStream(stream))

  def detectFromBytes(bytes: ValidatedBytes): ZIO[ContentTypeDetector, Nothing, Option[DetectionResult]] =
    ZIO.serviceWithZIO[ContentTypeDetector](_.detectFromBytes(bytes))

  def detectFromRawBytes(bytes: Chunk[Byte]): ZIO[ContentTypeDetector, String, Option[DetectionResult]] =
    ValidatedBytes(bytes) match {
      case Left(err)        => ZIO.fail(err)
      case Right(validated) => detectFromBytes(validated)
    }

  def detectFromFile(
    path: String
  ): ZIO[ContentTypeDetector & Scope, Throwable, (Option[DetectionResult], ZStream[Scope, Throwable, Byte])] =
    ZIO.serviceWithZIO[ContentTypeDetector](_.detectFromFile(path))

  def detectTypeOnly[R](
    stream: ZStream[R, Throwable, Byte]
  ): ZIO[ContentTypeDetector & R & Scope, Throwable, Option[DetectionResult]] =
    detectFromStream(stream).map(_._1)
}
