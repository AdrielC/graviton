package graviton

import zio.*

trait ContentTypeDetect:
  def detect(bytes: Bytes): IO[Throwable, Option[String]]
