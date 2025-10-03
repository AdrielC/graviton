package graviton.blob

import graviton.blob.Types.*
import io.github.iltotore.iron.zioJson.given
import _root_.zio.json.{DeriveJsonCodec, JsonCodec}

final case class BlobLocator(
  scheme: LocatorScheme,
  bucket: String,
  path: String,
):
  def render: String = s"$scheme://$bucket/$path"

object BlobLocator:
  given JsonCodec[BlobLocator] = DeriveJsonCodec.gen[BlobLocator]
