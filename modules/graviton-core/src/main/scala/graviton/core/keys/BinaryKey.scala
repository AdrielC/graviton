package graviton.core.keys

import zio.schema.{DeriveSchema, Schema}

sealed trait BinaryKey derives CanEqual:
  def bits: KeyBits

object BinaryKey:

  final case class Blob(bits: KeyBits)     extends BinaryKey
  final case class Block(bits: KeyBits)    extends BinaryKey
  final case class Chunk(bits: KeyBits)    extends BinaryKey
  final case class Manifest(bits: KeyBits) extends BinaryKey

  final case class View(
    bits: KeyBits,
    base: BinaryKey,
    transform: ViewTransform,
  ) extends BinaryKey

  object View:
    /** Views are transformations over manifest-typed keys. */
    def apply(key: KeyBits, transform: ViewTransform): Either[String, View] =
      for
        base <- BinaryKey.manifest(key)
        view <- BinaryKey.view(base, transform)
      yield view

  def blob(bits: KeyBits): Either[String, Blob] =
    if bits.size < 0 then Left("Blob size cannot be negative") else Right(Blob(bits))

  def block(bits: KeyBits): Either[String, Block] =
    if bits.size <= 0 then Left("Block size must be positive") else Right(Block(bits))

  def chunk(bits: KeyBits): Either[String, Chunk] =
    if bits.size < 0 then Left("Chunk size cannot be negative") else Right(Chunk(bits))

  def manifest(bits: KeyBits): Either[String, Manifest] =
    if bits.size < 0 then Left("Manifest size cannot be negative") else Right(Manifest(bits))

  /**
   * Deterministic view key derivation.
   *
   * We intentionally do NOT include tenancy/system ids in any view key derivation.
   */
  def view(base: BinaryKey, transform: ViewTransform): Either[String, View] =
    for
      validated <- ViewTransform.validateDeterministic(transform)
      bits      <- ViewKeyDerivation.derive(base, validated)
    yield View(bits = bits, base = base, transform = validated)

  given Schema[BinaryKey] = DeriveSchema.gen[BinaryKey]
