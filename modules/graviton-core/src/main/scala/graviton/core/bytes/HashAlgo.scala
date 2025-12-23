package graviton.core.bytes

import zio.schema.DeriveSchema
import java.security.MessageDigest
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import HashAlgo.AlgoName
import zio.{Chunk, NonEmptyChunk}
import scala.compiletime.ops.string.+
import graviton.core.keys.KeyBits
import scodec.bits.ByteVector
import java.nio.charset.StandardCharsets

enum HashAlgo(val hexLength: Int, jceNames: NonEmptyChunk[AlgoName]) derives CanEqual:
  case Sha256 extends HashAlgo(64, NonEmptyChunk("sha-256", "sha256"))
  case Sha1   extends HashAlgo(40, NonEmptyChunk("sha-1", "sha1"))
  case Blake3 extends HashAlgo(64, NonEmptyChunk("blake3", "blake3"))

  def hashBytes: Int = hexLength / 2

  def primaryName: AlgoName           = jceNames.head
  def alternateNames: Chunk[AlgoName] = jceNames.tail

  def equalsIgnoreCase(value: String): Boolean =
    primaryName.equalsIgnoreCase(value) ||
      alternateNames.exists(_.equalsIgnoreCase(value))

  def apply(value: Hasher.Digestable): Either[String, KeyBits] =
    for
      digest         <- Right(Digest.make(this))
      (digest, size) <- value match
                          case byteVector: ByteVector => digest(byteVector.toArray).map(digest => (digest, byteVector.length.toLong))
                          case chunk: Chunk[Byte]     => digest(chunk).map(digest => (digest, chunk.length.toLong))
                          case array: Array[Byte]     => digest(array).map(digest => (digest, array.length.toLong))
                          case string: String         =>
                            (for
                              bytes  <- ByteVector.fromHex(string).toRight(s"Invalid hex string '$string'")
                              digest <- Digest.fromBytes(bytes.toArray)
                            yield (digest, bytes.length.toLong))
                              .orElse(
                                digest(string.getBytes(StandardCharsets.UTF_8))
                                  .map(digest => (digest, string.length.toLong))
                              )
      keyBits        <- KeyBits.create(this, digest, size)
    yield keyBits

object HashAlgo:

  import scala.quoted.*

  given FromExpr[HashAlgo] = new FromExpr[HashAlgo] {
    def unapply(value: Expr[HashAlgo])(using Quotes): Option[HashAlgo] =
      value match
        case '{ ${ Expr(algo: HashAlgo) } } => Some(algo)
        case _                              => None
  }

  given ToExpr[HashAlgo] = new ToExpr[HashAlgo] {
    def apply(value: HashAlgo)(using Quotes): Expr[HashAlgo] =
      Expr(value)
  }

  def unapply(value: String): Option[HashAlgo] =
    value.split(":").headOption.flatMap(HashAlgo.fromString)

  transparent inline given Ordering[HashAlgo] = Ordering.by(_.primaryName)

  inline val algoNameRegexStr         = "[A-Za-z0-9_-]{1,64}"
  inline val lowerHexadecimalRegexStr = "[0-9a-f]{1,64}"
  inline val upperHexadecimalRegexStr = "[0-9A-F]{1,64}"

  final type AlgoNameRegexStr         = algoNameRegexStr.type
  final type LowerHexadecimalRegexStr = lowerHexadecimalRegexStr.type
  final type UpperHexadecimalRegexStr = upperHexadecimalRegexStr.type

  final val algoRegex: scala.util.matching.Regex             = algoNameRegexStr.r
  final val lowerHexadecimalRegex: scala.util.matching.Regex = lowerHexadecimalRegexStr.r
  final val upperHexadecimalRegex: scala.util.matching.Regex = upperHexadecimalRegexStr.r
  final val hexadecimalRegexStr                              = lowerHexadecimalRegexStr + "|" + upperHexadecimalRegexStr
  final val hexadecimalRegex: scala.util.matching.Regex      = hexadecimalRegexStr.r

  final val keyBitsRegexStr = "^" + algoNameRegexStr + ":" + hexadecimalRegexStr + ":[0-9]+$"

  final val keyBitsRegex: scala.util.matching.Regex = keyBitsRegexStr.r

  final type AlgoName = String :| Match[AlgoNameRegexStr]

  final type LowerHexadecimal = String :| Match[LowerHexadecimalRegexStr]

  final type UpperHexadecimal = String :| Match[UpperHexadecimalRegexStr]

  final type Hexadecimal = String :| (Match[LowerHexadecimalRegexStr + "|" + UpperHexadecimalRegexStr])

  final type KeyBitsRegexStr = keyBitsRegexStr.type

  final type KeyBits = String :| Match[KeyBitsRegexStr]

  given zio.schema.Schema[HashAlgo] = DeriveSchema.gen[HashAlgo]

  extension (algo: HashAlgo)

    def hasher(provider: Option[java.security.Provider] = None): Either[String, Hasher] =
      Hasher.hasher(algo, provider)

    def messageDigest(provider: Option[java.security.Provider] = None): Either[String, MessageDigest] =
      Hasher
        .hasher(algo, provider)
        .map(h => Hasher.unsafeMessageDigest(h.algo, provider))

  /** Preferred hash order (BLAKE3 first, then SHA-256 for FIPS environments, finally SHA-1 for legacy). */
  val preferredOrder: NonEmptyChunk[HashAlgo] = NonEmptyChunk(HashAlgo.Sha256, HashAlgo.Sha1, HashAlgo.Blake3)

  /** Primary build-time default (kept as BLAKE3 for performance). */
  val default: HashAlgo = preferredOrder.head

  /** Detects the first available provider from [[preferredOrder]]. */
  lazy val runtimeDefault: HashAlgo =
    Hasher.hasher(HashAlgo.default, None).fold(_ => HashAlgo.default, hasher => hasher.algo)

  /** Convenience hook to obtain both the algorithm and the instantiated hasher following [[preferredOrder]]. */
  def runtimeHasher: Either[String, Hasher] =
    Hasher.hasher(HashAlgo.default, None)

  def fromString(value: String): Option[HashAlgo] =
    HashAlgo.values.find(_.equalsIgnoreCase(value))
