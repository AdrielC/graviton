package torrent
package utils

import java.net.URLConnection
import java.nio.file.{ Files, Paths }

import zio.*
import zio.metrics.*
import zio.stream.*

/**
 * A trait for detecting content types of binary data
 */
trait ContentTypeDetect:
  self =>

  /**
   * Detect the content type of a byte array
   *
   * @param data
   *   The binary data to analyze
   * @param hint
   *   Additional hints (like filename)
   * @return
   *   Detected content type
   */
  def detect(data: Chunk[Byte], hint: Hint): IO[Throwable, MediaType]

  /**
   * Create a pipeline that detects content type from a stream of bytes
   *
   * @param hint
   *   Additional hints (like filename)
   * @return
   *   A pipeline that outputs the detected content type
   */
  def detectStream(hint: Hint): ZPipeline[Any, Throwable, Byte, MediaType] =
    ZPipeline.fromFunction(
      _.transduce(ZSink.take(512))
        .map((chunk: Chunk[Byte]) => detect(chunk, hint))
        .flatMap(ZStream.fromZIO(_))
    )

  /**
   * Try another detector if this one fails
   */
  def orElse(next: ContentTypeDetect): ContentTypeDetect = new ContentTypeDetect:
    override def detect(data: Chunk[Byte], hint: Hint): IO[Throwable, MediaType] =
      self.detect(data, hint).catchAll(_ => next.detect(data, hint))

object ContentTypeDetect:
  /**
   * A detector that always returns "application/octet-stream"
   */
  val none: ContentTypeDetect = new ContentTypeDetect:

    override def detect(data: Chunk[Byte], hint: Hint): IO[Throwable, MediaType] =
      ZIO.succeed(MediaType.application.octetStream)

    override def detectStream(hint: Hint): ZPipeline[Any, Throwable, Byte, MediaType] =
      ZStream.succeed(MediaType.application.octetStream).channel.toPipeline

    override def orElse(next: ContentTypeDetect): ContentTypeDetect = next

  /**
   * An implementation using Java NIO Files.probeContentType
   */
  val probeFileType: ContentTypeDetect =
    (_: Chunk[Byte], hint: Hint) =>
      ZIO.suspend {
        hint.get(Attribute.FileName) match
          case Some(filename: String) =>
            SecurityUtils
              .validatePathV(filename)
              .toZIO
              .flatMap { path =>
                ZIO.attempt {
                  Option(Files.probeContentType(path.value))
                    .orElse(Option(URLConnection.getFileNameMap.getContentTypeFor(filename)))
                    .getOrElse("application/octet-stream")
                    .asInstanceOf[String]
                }.flatMap(contentType => ZIO.fromEither(MediaType.either(contentType)))
                  .orElse(ZIO.succeed(MediaType.application.octetStream))
              }
              .catchAll(_ => ZIO.succeed(MediaType.application.octetStream))
          case None                   =>
            ZIO.succeed(MediaType.application.octetStream)
      }

  /**
   * A simple implementation based on magic numbers
   */
  val basic: ContentTypeDetect =
    (data: Chunk[Byte], _: Hint) =>
      ZIO.succeed:
        if data.isEmpty then MediaType.application.octetStream
        else if data.startsWith(Chunk(0x89, 0x50, 0x4e, 0x47)) then MediaType.image.png
        else if data.startsWith(Chunk(0xff, 0xd8, 0xff)) then MediaType.image.jpeg
        else if data.startsWith(Chunk(0x47, 0x49, 0x46)) then MediaType.image.gif
        else if data.startsWith(Chunk(0x25, 0x50, 0x44, 0x46)) then MediaType.application.pdf
        else if data.startsWith(Chunk(0x50, 0x4b, 0x03, 0x04)) then MediaType.application.zip
        else
          // Look for text content
          val isText = data.forall(b => b >= 32 && b <= 126 || b == '\n' || b == '\r' || b == '\t')
          if isText then MediaType.text.plain
          else MediaType.application.octetStream

  /**
   * A detector that always returns a constant content type
   */
  def constant(ct: MediaType): ContentTypeDetect = (_, _) => ZIO.succeed(ct)

  /**
   * Create a detector from a function
   */
  def apply(f: (Chunk[Byte], Hint) => IO[Throwable, MediaType]): ContentTypeDetect =
    (data: Chunk[Byte], hint: Hint) => f(data, hint)

  /**
   * default implementation combining multiple strategies
   */
  val default: ContentTypeDetect =
    probeFileType.orElse(basic).orElse(none)

  // Register metrics for content type detection
  val detectTotal         = Metric.counter("torrent_detect_content_type_total")
  val detectTypeFailure   = Metric.counter("torrent_detect_content_type_failure")
  val detectContentTypeMs = Metric.gauge("torrent_detect_content_type_duration_ms")
