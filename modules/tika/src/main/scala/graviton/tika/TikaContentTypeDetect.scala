package graviton.tika

import graviton.*
import zio.*
import org.apache.tika.Tika

final class TikaContentTypeDetect extends ContentTypeDetect:
  private val tika = new Tika()

  def detect(bytes: Bytes): IO[Throwable, Option[String]] =
    bytes.runCollect.map(ch => Option(tika.detect(ch.toArray)))

object TikaContentTypeDetect:
  val live: ULayer[ContentTypeDetect] =
    ZLayer.succeed(new TikaContentTypeDetect)
