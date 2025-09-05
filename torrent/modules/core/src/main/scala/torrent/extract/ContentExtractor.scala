package torrent
package extract

import zio.*
import zio.stream.*

/**
 * Error type for content extraction operations
 */
sealed trait ExtractionError extends Throwable:
  def message: String
  override def getMessage: String = message

object ExtractionError:
  /**
   * The format is not supported by this extractor
   */
  final case class UnsupportedFormat(mimeType: MediaType) extends ExtractionError:
    override def message: String = s"Unsupported format: ${mimeType}"

  /**
   * The content is corrupt or malformed
   */
  final case class MalformedContent(mimeType: MediaType, cause: Throwable) extends ExtractionError:
    override def message: String     = s"Malformed content of type ${mimeType}: ${cause.getMessage}"
    override def getCause: Throwable = cause

  /**
   * The content is password protected
   */
  final case class PasswordProtected(mimeType: MediaType) extends ExtractionError:
    override def message: String = s"Content of type ${mimeType} is password protected"

  /**
   * Generic extraction error
   */
  final case class GenericError(message: String, cause: Option[Throwable] = None) extends ExtractionError:
    override def getCause: Throwable = cause.orNull

/**
 * Interface for extracting content (text, metadata) from binary data
 */
trait ContentExtractor:
  /**
   * Extract plain text from binary data
   *
   * @param binary
   *   The binary data stream
   * @param hint
   *   Hints about the content (e.g., filename, mimetype)
   * @return
   *   The extracted text or an error
   */
  def extractText(binary: ZStream[Any, Throwable, Byte], hint: Hint): IO[ExtractionError, String]

  /**
   * Extract metadata from binary data
   *
   * @param binary
   *   The binary data stream
   * @param hint
   *   Hints about the content (e.g., filename, mimetype)
   * @return
   *   Map of metadata key-value pairs or an error
   */
  def extractMetadata(binary: ZStream[Any, Throwable, Byte], hint: Hint): IO[ExtractionError, Map[String, String]]

  /**
   * Check if this extractor supports the given MIME type
   *
   * @param mimeType
   *   The MIME type to check
   * @return
   *   true if this extractor can handle this MIME type
   */
  def supports(mimeType: MediaType): Boolean

/**
 * Companion object with utility methods
 */
object ContentExtractor:
  /**
   * A ContentExtractor that handles no formats
   */
  val empty: ContentExtractor = new ContentExtractor:
    override def extractText(binary: ZStream[Any, Throwable, Byte], hint: Hint): IO[ExtractionError, String] =
      ZIO.fail(
        ExtractionError.UnsupportedFormat(
          hint
            .get(Attribute.ContentType)
            .flatMap(MediaType.either(_).toOption)
            .getOrElse(MediaType.application.octetStream)
        )
      )

    override def extractMetadata(binary: ZStream[Any, Throwable, Byte],
                                 hint:   Hint
    ): IO[ExtractionError, Map[String, String]] =
      ZIO.fail(
        ExtractionError.UnsupportedFormat(
          hint
            .get(Attribute.ContentType)
            .flatMap(MediaType.either(_).toOption)
            .getOrElse(MediaType.application.octetStream)
        )
      )

    override def supports(mimeType: MediaType): Boolean = false
