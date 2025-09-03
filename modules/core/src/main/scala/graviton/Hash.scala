package graviton

import zio.Chunk
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

type Digest = Chunk[Byte] :| (MinLength[16] & MaxLength[64])

final case class Hash(bytes: Digest, algo: HashAlgorithm):
  def hex: String =
    bytes
      .foldLeft(new StringBuilder) { (sb, b) => sb.append(f"$b%02x") }
      .toString
