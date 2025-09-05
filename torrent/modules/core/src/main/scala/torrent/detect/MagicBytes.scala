package torrent.detect

import zio.stream.ZStream
import zio.{ Chunk, ZIO }

object MagicBytes {
  val PNG_HEADER   = Chunk(0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte)
  val PDF_HEADER   = Chunk('%', 'P', 'D', 'F', '-').map(_.toByte)
  val RTF_HEADER   = Chunk('{', '\\', 'r', 't', 'f').map(_.toByte)
  val TIF_HEADER   = Chunk('I', 'I', 0x2a, 0x00).map(_.toByte)
  val DOCX_ZIP_SIG = Chunk(0x50, 0x4b, 0x03, 0x04).map(_.toByte)
  val TEXT_CHARS   = (0x20 to 0x7e).toSet ++ Set(0x0a, 0x0d, 0x09) // ASCII printable + whitespace

  def isText(bytes: Chunk[Byte]): Boolean =
    bytes.nonEmpty && bytes.forall(b => TEXT_CHARS.contains(b & 0xff))

  def isCSV(bytes: ContentTypeDetector.ValidatedBytes): Boolean = {
    val content = bytes.underlying
    val text    = new String(content.toArray, "UTF-8")
    val lines   = text.split("\n")
    if (lines.length < 2) false
    else {
      val firstLine  = lines(0)
      val commaCount = firstLine.count(_ == ',')
      lines.drop(1).forall(line => line.count(_ == ',') == commaCount)
    }
  }

  def looksLikeJson(bytes: ContentTypeDetector.ValidatedBytes): Boolean = {
    val content = bytes.underlying
    val text    = new String(content.toArray, "UTF-8").trim
    (text.startsWith("{") && text.endsWith("}")) ||
    (text.startsWith("[") && text.endsWith("]"))
  }

  def detectOfficeTypeFromZip(bytes: ContentTypeDetector.ValidatedBytes): Option[ContentType] =
    // This is a simplified implementation - in a real scenario you'd parse the ZIP structure
    // For now, we'll return DOCX as a default for ZIP files
    Some(ContentType.DOCX)

  def detectOfficeTypeFromStream[R](stream: ZStream[R, Throwable, Byte]): ZIO[R, Throwable, Option[ContentType]] =
    // This is a simplified implementation - in a real scenario you'd parse the ZIP structure
    ZIO.succeed(Some(ContentType.DOCX))
}
