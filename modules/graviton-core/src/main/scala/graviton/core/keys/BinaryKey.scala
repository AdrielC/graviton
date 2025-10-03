package graviton.core.keys

sealed trait BinaryKey derives CanEqual:
  def bits: KeyBits

object BinaryKey:
  final case class Blob(bits: KeyBits)                           extends BinaryKey
  final case class Block(bits: KeyBits)                          extends BinaryKey
  final case class Chunk(bits: KeyBits)                          extends BinaryKey
  final case class Manifest(bits: KeyBits)                       extends BinaryKey
  final case class View(bits: KeyBits, transform: ViewTransform) extends BinaryKey

  def blob(bits: KeyBits): Either[String, Blob] =
    if bits.size < 0 then Left("Blob size cannot be negative") else Right(Blob(bits))

  def block(bits: KeyBits): Either[String, Block] =
    if bits.size <= 0 then Left("Block size must be positive") else Right(Block(bits))

  def chunk(bits: KeyBits): Either[String, Chunk] =
    if bits.size < 0 then Left("Chunk size cannot be negative") else Right(Chunk(bits))

  def manifest(bits: KeyBits): Either[String, Manifest] =
    if bits.size < 0 then Left("Manifest size cannot be negative") else Right(Manifest(bits))

  def view(bits: KeyBits, transform: ViewTransform): Either[String, View] =
    blob(bits).map(_ => View(bits, transform))
