package graviton.core.bytes

import zio.schema.Schema
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import zio.Chunk
import java.security.Provider as JProvider
import scodec.bits.ByteVector

trait Provider:
  def getInstance(hashAlgo: HashAlgo): Either[String, Hasher]

private[graviton] final class ProviderImpl(provider: JProvider) extends Provider:
  override def getInstance(hashAlgo: HashAlgo): Either[String, Hasher] =
    Hasher.hasher(hashAlgo, Some(provider))

opaque type Digest <: Chunk[Byte] :| (MinLength[16] & MaxLength[64]) =
  Chunk[Byte] :| (MinLength[16] & MaxLength[64])

type Digestable = Chunk[Byte] | Array[Byte] | String

object Digest:

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
      .fromHex(value)
      .toRight(s"Invalid hex digest '$value'")
      .map(_.toArray)
      .flatMap(fromBytes)

  extension (digest: Digest)
    def value: Chunk[Byte]     = digest
    def bytes: Array[Byte]     = value.toArray
    def byteVector: ByteVector = ByteVector(value.toArray)
    def hex: String            = byteVector.toHex

  def apply(algo: HashAlgo)(value: Hasher.Digestable): Either[String, Digest] =
    algo.hasher(None).flatMap((hasher: Hasher) => hasher.update(value).digest)

  given Schema[Digest] = Schema
    .chunk[Byte]
    .transformOrFail(
      bytes => bytes.refineEither[MinLength[16] & MaxLength[64]],
      digest => Right(digest),
    )

  def make(algo: HashAlgo, provider: Option[Provider] = None)(value: Hasher.Digestable): Either[String, Digest] =
    provider
      .map(p => p.getInstance(algo))
      .getOrElse(Hasher.hasher(algo, None))
      .flatMap((hasher: Hasher) => hasher.update(value).digest)
