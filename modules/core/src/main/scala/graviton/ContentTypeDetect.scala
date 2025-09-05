package graviton

import zio.*

trait ContentTypeDetect:
  def detect(bytes: Bytes): IO[Throwable, Option[String]]

object ContentTypeDetect:
  private val PNG  = Chunk.fromArray(Array[Byte](0x89.toByte, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A))
  private val PDF  = Chunk.fromArray("%PDF".getBytes("ISO-8859-1"))
  private val RTF  = Chunk.fromArray("{\\rtf".getBytes("ISO-8859-1"))
  private val ZIP  = Chunk.fromArray(Array[Byte](0x50, 0x4B, 0x03, 0x04))

  val live: UIO[ContentTypeDetect] = ZIO.succeed:
    new ContentTypeDetect:
      def detect(bytes: Bytes): IO[Throwable, Option[String]] =
        val headerN = 8192
        bytes.take(headerN).runCollect.map { ch =>
          val header = ch
          if startsWith(header, PNG) then Some("image/png")
          else if startsWith(header, PDF) then Some("application/pdf")
          else if startsWith(header, RTF) then Some("application/rtf")
          else if startsWith(header, ZIP) then guessOffice(header).orElse(Some("application/zip"))
          else if looksText(header) then Some("text/plain")
          else None
        }

  private def startsWith(h: Chunk[Byte], prefix: Chunk[Byte]): Boolean =
    if h.length < prefix.length then false
    else h.take(prefix.length) == prefix

  private def looksText(h: Chunk[Byte]): Boolean =
    val arr = h.toArray
    var i = 0
    while i < arr.length do
      val b = arr(i)
      if b < 0x09 || (b > 0x0D && b < 0x20) then return false
      i += 1
    true

  private def guessOffice(h: Chunk[Byte]): Option[String] =
    // Heuristic: presence of "[Content_Types].xml" in first 8KB
    val s = new String(h.toArray, "ISO-8859-1")
    if s.contains("[Content_Types].xml") then Some("application/vnd.openxmlformats-officedocument")
    else None
