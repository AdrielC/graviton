package torrent

import zio.Chunk

object MagicBytes {
  val PNG_HEADER = Chunk(0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte)
  val PDF_HEADER = Chunk('%', 'P', 'D', 'F', '-').map(_.toByte)
  val TEXT_CHARS = (0x20 to 0x7e).toSet ++ Set(0x0a, 0x0d, 0x09) // ASCII printable + whitespace

  def isText(bytes: Chunk[Byte]): Boolean =
    bytes.nonEmpty && bytes.forall(b => TEXT_CHARS.contains(b & 0xff))
}
