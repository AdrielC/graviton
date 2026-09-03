package graviton.core.keys

import graviton.core.bytes.{Digest, HashAlgo, HashedContent}
import graviton.core.types.ContentLength
import graviton.shared.cas.ContentKeyText as ContentKeyWire
import zio.NonEmptyChunk
import zio.prelude.Validation
import zio.schema.{DeriveSchema, Schema}

import scala.util.Try

final case class KeyBits private[graviton] (algo: HashAlgo, digest: Digest, size: ContentLength):
  /** Stable, round-trippable text form used by the CLI and HTTP API. */
  def render: ContentKeyId =
    ContentKeyId.fromKeyBits(this)

object KeyBits:
  /** Construct a key only from a digest and size observed by one hasher. */
  def fromHashed(content: HashedContent): KeyBits =
    KeyBits(content.hash.algo, content.hash.bytes, content.size)

  /**
   * Reconstruct a key claim read from trusted syntax or persistence.
   *
   * This validates the representation, but only streaming the addressed bytes
   * through verification can prove that the claim is true.
   */
  private[graviton] def fromClaimed(algo: HashAlgo, digest: Digest, size: ContentLength): Either[String, KeyBits] =
    if digest.length != algo.hashBytes then Left("Digest length mismatch")
    else Right(KeyBits(algo, digest, size))

  /** Validate an untrusted wire, database, or counter value before key construction. */
  private[graviton] def fromLong(algo: HashAlgo, digest: Digest, size: Long): Either[String, KeyBits] =
    ContentLength.either(size).flatMap(fromClaimed(algo, digest, _))

  /** Validate all independent content-key fields before checking dependent invariants. */
  def fromString(value: String): Validation[KeyBitsError, KeyBits] =
    Validation
      .fromEither(ContentKeyWire.fields(value).left.map(KeyBitsError.Malformed.apply))
      .flatMap { fields =>
        Validation
          .validateWith(
            validateAlgorithm(fields.algorithm),
            validateDigest(fields.digestHex),
            validateSize(fields.sizeText),
          )((_, _, _))
          .flatMap { case (algo, digest, size) =>
            Validation.fromEither(
              fromClaimed(algo, digest, size).left.map(_ => KeyBitsError.DigestLengthMismatch(algo, algo.hashBytes, digest.length))
            )
          }
      }

  /** Fail-fast adapter for transports and persistence APIs that require `Either`. */
  def parse(value: String): Either[String, KeyBits] =
    fromString(value).toEitherWith(KeyBitsError.render)

  private def validateAlgorithm(value: String): Validation[KeyBitsError, HashAlgo] =
    Validation.fromEither(
      HashAlgo.fromString(value).toRight(KeyBitsError.UnsupportedAlgorithm(value))
    )

  private def validateDigest(value: String): Validation[KeyBitsError, Digest] =
    val parsed =
      if value.nonEmpty && value.forall(character => isAsciiDigit(character) || (character >= 'a' && character <= 'f')) then
        Digest.fromString(value)
      else Left("Digest must contain lowercase hexadecimal characters only")

    Validation.fromEither(parsed.left.map(KeyBitsError.InvalidDigest.apply))

  private def validateSize(value: String): Validation[KeyBitsError, ContentLength] =
    val parsed =
      Either
        .cond(value.nonEmpty && value.forall(isAsciiDigit), value, s"Invalid byte length '$value'")
        .flatMap(text => Try(text.toLong).toEither.left.map(_ => s"Invalid byte length '$value'"))
        .flatMap(ContentLength.either)

    Validation.fromEither(parsed.left.map(KeyBitsError.InvalidSize.apply))

  private def isAsciiDigit(character: Char): Boolean =
    character >= '0' && character <= '9'

  inline given Schema[KeyBits] = DeriveSchema.gen[KeyBits]

/**
 * Canonical, bounded identifier text for a content key.
 *
 * Values cannot be manufactured from arbitrary strings. Construction either
 * renders an already-valid [[KeyBits]] value or parses and canonicalizes an
 * untrusted wire value through [[KeyBits.fromString]].
 */
opaque type ContentKeyId <: String = String

object ContentKeyId:
  final val MaxLength: Int = ContentKeyWire.MaxWireLength

  private[keys] def fromKeyBits(value: KeyBits): ContentKeyId =
    ContentKeyWire.render(value.algo.primaryName, value.digest.hex.value, value.size)

  def fromString(value: String): Validation[KeyBitsError, ContentKeyId] =
    KeyBits.fromString(value).map(fromKeyBits)

  def parse(value: String): Either[String, ContentKeyId] =
    fromString(value).toEitherWith(KeyBitsError.render)

  extension (value: ContentKeyId) def value: String = value

enum KeyBitsError derives CanEqual:
  case Malformed(reason: String)
  case UnsupportedAlgorithm(value: String)
  case InvalidDigest(reason: String)
  case InvalidSize(reason: String)
  case DigestLengthMismatch(algo: HashAlgo, expectedBytes: Int, actualBytes: Int)

  def message: String =
    this match
      case KeyBitsError.Malformed(reason)                            => reason
      case KeyBitsError.UnsupportedAlgorithm(value)                  => s"Unsupported hash algorithm '$value'"
      case KeyBitsError.InvalidDigest(reason)                        => reason
      case KeyBitsError.InvalidSize(reason)                          => reason
      case KeyBitsError.DigestLengthMismatch(algo, expected, actual) =>
        s"${algo.primaryName} requires $expected digest bytes, got $actual"

object KeyBitsError:
  def render(errors: NonEmptyChunk[KeyBitsError]): String =
    errors.map(_.message).mkString("; ")
