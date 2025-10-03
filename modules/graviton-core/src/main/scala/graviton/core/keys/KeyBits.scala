package graviton.core.keys

import graviton.core.bytes.{Digest, HashAlgo}
import zio.schema.{DeriveSchema, Schema}

final case class KeyBits(algo: HashAlgo, digest: Digest, size: Long)

object KeyBits:
  def create(algo: HashAlgo, digest: Digest, size: Long): Either[String, KeyBits] =
    if size < 0 then Left("Size must be non-negative")
    else if digest.algo != algo then Left("Digest algorithm mismatch")
    else Right(KeyBits(algo, digest, size))

  given Schema[KeyBits] = DeriveSchema.gen[KeyBits]
