package graviton.core.keys

import graviton.core.types.{BlockSize, FileSize, MaxBlockBytes}
import zio.schema.{DeriveSchema, Schema}

sealed trait BinaryKey derives CanEqual:
  def bits: KeyBits

object BinaryKey:

  final case class Blob private[graviton] (bits: KeyBits)     extends BinaryKey
  final case class Block private[graviton] (bits: KeyBits)    extends BinaryKey
  final case class Chunk private[graviton] (bits: KeyBits)    extends BinaryKey
  final case class Manifest private[graviton] (bits: KeyBits) extends BinaryKey

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
    FileSize.either(bits.size).map(_ => Blob(bits)).left.map(message => s"Invalid blob size: $message")

  def block(bits: KeyBits): Either[String, Block] =
    if bits.size > MaxBlockBytes.toLong then Left(s"Block size must not exceed $MaxBlockBytes bytes, received ${bits.size}")
    else
      BlockSize
        .either(bits.size.toInt)
        .map(_ => Block(bits))
        .left
        .map(message => s"Invalid block size: $message")

  def chunk(bits: KeyBits): Either[String, Chunk] =
    Right(Chunk(bits))

  def manifest(bits: KeyBits): Either[String, Manifest] =
    Right(Manifest(bits))

  /**
   * Deterministic view key derivation.
   *
   * We intentionally do NOT include tenancy/system ids in any view key derivation.
   */
  def view(base: BinaryKey, transform: ViewTransform): Either[String, View] =
    base match
      case manifest: Manifest =>
        for
          validated <- ViewTransform.validateDeterministic(transform)
          bits      <- ViewKeyDerivation.derive(manifest, validated)
        yield View(bits = bits, base = manifest, transform = validated)
      case _                  => Left("View base key must be a manifest key")

  given Schema[BinaryKey] = DeriveSchema.gen[BinaryKey]
