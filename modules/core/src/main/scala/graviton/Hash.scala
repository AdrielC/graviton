package graviton

import zio.schema.{DeriveSchema, Schema}
import scodec.bits.ByteVector
import graviton.domain.HashBytes

final case class Hash(bytes: HashBytes, algo: HashAlgorithm):
  def hex: String = ByteVector(bytes.toArray).toHex

object Hash:
  given Schema[Hash] = DeriveSchema.gen[Hash]
