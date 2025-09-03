package graviton

import zio.Chunk

final case class Hash(bytes: Chunk[Byte], algo: HashAlgorithm):
  def hex: String =
    bytes
      .foldLeft(new StringBuilder) { (sb, b) => sb.append(f"$b%02x") }
      .toString
