package graviton.blob

import graviton.blob.Types.*
import io.github.iltotore.iron.zioJson.given
import _root_.zio.json.{DeriveJsonCodec, JsonCodec}

final case class BinaryKey(
  algo: Algo,
  hash: HexLower,
  size: Size,
  mime: Option[Mime],
)

object BinaryKey:
  given JsonCodec[BinaryKey]                                                                      = DeriveJsonCodec.gen[BinaryKey]
  def make(algo: Algo, hash: HexLower, size: Size, mime: Option[Mime]): Either[String, BinaryKey] =
    Types.validateDigest(algo, hash).map(_ => BinaryKey(algo, hash, size, mime))
