package graviton.core.bytes

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import zio.Chunk
import zio.schema.Schema
import java.security.Provider as JProvider
import graviton.core.keys.KeyBits
import scodec.bits.ByteVector
import graviton.core.types.HexLower

trait Provider:
  def getInstance(hashAlgo: HashAlgo): Either[String, Hasher]

private[graviton] final class ProviderImpl(provider: JProvider) extends Provider:
  override def getInstance(hashAlgo: HashAlgo): Either[String, Hasher] =
    Hasher.hasher(hashAlgo, Some(provider))

/**
 * A decoded digest value. The supported checksum and hash domain is 16 to 32 bytes.
 * Executable Graviton hashes use the narrower [[HashBytes]] subtype.
 */
opaque type Digest <: Chunk[Byte] = Chunk[Byte] :| (MinLength[16] & MaxLength[32])

object Digest:

  import scala.quoted.*

  given FromExpr[Digest] = new FromExpr[Digest] {
    def unapply(value: Expr[Digest])(using Quotes): Option[Digest] =
      value match
        case '{ ${ Expr(digest: Digest) } } => Some(digest)
        case _                              => None
  }

  given ToExpr[Digest] = new ToExpr[Digest] {
    def apply(value: Digest)(using Quotes): Expr[Digest] =
      val encoded = Expr(encodeLower(value))
      '{
        Digest.fromString($encoded) match
          case Right(digest) => digest
          case Left(message) => throw new AssertionError(s"Compiler-emitted digest no longer parses: $message")
      }
  }

  def unapply(value: String): Option[Digest] = fromString(value).toOption

  def fromChunk(value: Chunk[Byte]): Either[String, Digest] =
    value
      .refineEither[MinLength[16] & MaxLength[32]]
      .map(refined => Digest(Chunk.fromIterable(refined)))

  /** Copy bytes entering from a bounded Java interop boundary. */
  private[graviton] def fromArrayCopy(value: Array[Byte]): Either[String, Digest] =
    value
      .refineEither[MinLength[16] & MaxLength[32]]
      .map(refined => Digest(Chunk.fromArray(refined.clone())))

  def fromString(value: String): Either[String, Digest] =
    ByteVector
      .fromHex(value, scodec.bits.Bases.Alphabets.HexLowercase)
      .orElse(ByteVector.fromHex(value, scodec.bits.Bases.Alphabets.HexUppercase))
      .toRight(s"Invalid hex digest '$value'")
      .flatMap(b => fromChunk(Chunk.fromIterable(b.toIterable)))

  extension (digest: Digest)
    def hex: HexLower =
      HexLower.either(encodeLower(digest)) match
        case Right(hex)    => hex
        case Left(message) => throw new AssertionError(s"Digest produced invalid lowercase hexadecimal: $message")

    /**
     * Copy this bounded digest for a Java API that requires `Array[Byte]`.
     * The mutable array is fresh, remains at most 32 bytes, and must not escape
     * the immediate JDBC, JCA, protobuf, filesystem, or SDK call.
     */
    private[graviton] def toInteropArray: Array[Byte] = digest.toArray

  private[graviton] def apply(value: Chunk[Byte]): Digest = assume(value)

  private val LowerHexDigits = "0123456789abcdef"

  private def encodeLower(digest: Digest): String =
    val output = new java.lang.StringBuilder(digest.length * 2)
    digest.foreach { byte =>
      val unsigned = byte & 0xff
      val _        = output.append(LowerHexDigits.charAt(unsigned >>> 4))
      val _        = output.append(LowerHexDigits.charAt(unsigned & 0x0f))
    }
    output.toString

  /**
   * Compatibility sentinel retained for 0.8.x callers. It is a valid bounded
   * 32-byte value rather than an invalid empty refinement escape.
   */
  @deprecated("Use an Option when a hash may be absent", "0.9.0")
  def empty: Digest = Digest(Chunk.fill(32)(0.toByte))

  def apply(algo: HashAlgo)(value: Hasher.Digestable): Either[String, Digest] =
    algo.hasher(None).flatMap((hasher: Hasher) => hasher.updateLegacy(value).digest)

  def digest(value: Hasher.Digestable): Either[String, KeyBits] =
    HashAlgo.default(value)

  given Schema[Digest] = Schema
    .chunk[Byte]
    .transformOrFail(
      bytes => Digest.fromChunk(bytes),
      digest => Right(digest),
    )

  def make(algo: HashAlgo, provider: Option[Provider] = None)(value: Hasher.Digestable): Either[String, Digest] =
    provider
      .map(p => p.getInstance(algo))
      .getOrElse(Hasher.hasher(algo, None))
      .flatMap((hasher: Hasher) => hasher.updateLegacy(value).digest)
