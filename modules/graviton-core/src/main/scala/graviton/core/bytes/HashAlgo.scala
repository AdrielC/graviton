package graviton.core.bytes

import zio.schema.DeriveSchema

enum HashAlgo(val hexLength: Int) derives CanEqual:
  case Sha256 extends HashAlgo(64)
  case Blake3 extends HashAlgo(64)

object HashAlgo:
  given zio.schema.Schema[HashAlgo] = DeriveSchema.gen[HashAlgo]

  def fromString(value: String): Option[HashAlgo] =
    value.toLowerCase match
      case "sha256" => Some(HashAlgo.Sha256)
      case "blake3" => Some(HashAlgo.Blake3)
      case _        => None
