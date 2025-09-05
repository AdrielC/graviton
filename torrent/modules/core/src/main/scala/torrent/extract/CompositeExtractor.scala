package torrent
package extract

import zio.*
import zio.stream.*

/**
 * Combines multiple ContentExtractor implementations to handle different file
 * formats
 */
class CompositeExtractor(extractors: List[ContentExtractor]) extends ContentExtractor:
  /**
   * Find the first extractor that supports the given MIME type
   */
  private def findExtractor(mimeType: MediaType): Option[ContentExtractor] =
    extractors.find(_.supports(mimeType))

  /**
   * Extract text using the first compatible extractor
   */
  override def extractText(binary: ZStream[Any, Throwable, Byte], hint: Hint): IO[ExtractionError, String] =
    for
      mimeType  <- ZIO.succeed(getMimeType(hint))
      extractor <- findExtractor(mimeType) match
                     case Some(ex) => ZIO.succeed(ex)
                     case None     => ZIO.fail(ExtractionError.UnsupportedFormat(mimeType))
      result    <- extractor.extractText(binary, hint)
    yield result

  /**
   * Extract metadata using the first compatible extractor
   */
  override def extractMetadata(binary: ZStream[Any, Throwable, Byte],
                               hint:   Hint
  ): IO[ExtractionError, Map[String, String]] =
    for
      mimeType  <- ZIO.succeed(getMimeType(hint))
      extractor <- findExtractor(mimeType) match
                     case Some(ex) => ZIO.succeed(ex)
                     case None     => ZIO.fail(ExtractionError.UnsupportedFormat(mimeType))
      result    <- extractor.extractMetadata(binary, hint)
    yield result

  /**
   * Check if any extractor supports the given MIME type
   */
  override def supports(mimeType: MediaType): Boolean =
    extractors.exists(_.supports(mimeType))

  /**
   * Get MIME type from the hint or fall back to octet-stream
   */
  private def getMimeType(hint: Hint): MediaType =
    hint
      .get(Attribute.ContentType)
      .flatMap(MediaType.either(_).toOption)
      .getOrElse(MediaType.application.octetStream)

object CompositeExtractor:
  /**
   * Create a new composite extractor from a list of extractors
   */
  def apply(extractors: ContentExtractor*): CompositeExtractor =
    new CompositeExtractor(extractors.toList)

  /**
   * Create a layer that provides a ContentExtractor from multiple extractors
   */
  def layer(extractors: ContentExtractor*): ULayer[ContentExtractor] =
    ZLayer.succeed(apply(extractors*))

  /**
   * Create a layer that provides a ContentExtractor from multiple extractor
   * layers
   */
  def fromLayers[R](layers: ZLayer[R, Nothing, ContentExtractor]*): ZLayer[R, Nothing, ContentExtractor] =
    ZLayer.fromZIO {
      ZIO
        .foreach(layers) { layer =>
          ZIO.service[ContentExtractor].provideSomeLayer(layer)
        }
        .map(extractors => apply(extractors*))
    }
