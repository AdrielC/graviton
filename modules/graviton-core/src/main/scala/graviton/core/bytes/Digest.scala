package graviton.core.bytes

import zio.schema.Schema
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import zio.Chunk
import java.security.Provider as JProvider
import scodec.bits.ByteVector
import graviton.core.keys.KeyBits
import graviton.core.types.HexLower

trait Provider:
  def getInstance(hashAlgo: HashAlgo): Either[String, Hasher]

private[graviton] final class ProviderImpl(provider: JProvider) extends Provider:
  override def getInstance(hashAlgo: HashAlgo): Either[String, Hasher] =
    Hasher.hasher(hashAlgo, Some(provider))

opaque type Digest <: Chunk[Byte] = Chunk[Byte] :| (MinLength[16] & MaxLength[64])

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
      '{ Digest(zio.Chunk.fromArray(${ Expr(value.bytes) })) }
  }

  def unapply(value: String): Option[Digest] =
    fromString(value).toOption

  def empty: Digest = Chunk.empty.asInstanceOf[Digest]

  private[graviton] def apply(value: Chunk[Byte]): Digest = assume(value)

  def fromChunk(value: Chunk[Byte]): Either[String, Digest] =
    value
      .refineEither[MinLength[16] & MaxLength[64]]
      .map(_.asInstanceOf[Digest])

  def fromBytes(value: Array[Byte]): Either[String, Digest] =
    fromChunk(Chunk.fromArray(value))

  def fromString(value: String): Either[String, Digest] =
    ByteVector
      .fromHex(value, scodec.bits.Bases.Alphabets.HexLowercase)
      .orElse(ByteVector.fromHex(value, scodec.bits.Bases.Alphabets.HexUppercase))
      .toRight(s"Invalid hex digest '$value'")
      .map(b => Digest(Chunk.fromArray(b.toArray)))

  extension (digest: Digest)
    def value: Chunk[Byte]     = digest
    def bytes: Array[Byte]     = value.toArray
    def byteVector: ByteVector = ByteVector(value.toArray)
    def hex: HexLower          = HexLower.applyUnsafe(byteVector.toHex)

  def apply(algo: HashAlgo)(value: Hasher.Digestable): Either[String, Digest] =
    algo.hasher(None).flatMap((hasher: Hasher) => hasher.update(value).digest)

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
      .flatMap((hasher: Hasher) => hasher.update(value).digest)
