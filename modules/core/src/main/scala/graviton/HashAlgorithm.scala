package graviton

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scodec.bits.ByteVector
import zio.schema.{DeriveSchema, Schema}

import scala.util.Try

/**
 * Supported hashing algorithms for content addressed storage. Each algorithm
 * exposes metadata about its digest and can compute hashes directly.
 */
sealed trait HashAlgorithm:

  /** Canonical lowercase name of the algorithm (e.g. "sha256"). */
  def canonicalName: String

  /** Number of hex characters in the digest (e.g. 64 for SHA-256). */
  def hexDigestLength: Int

  /** Obtain a new `MessageDigest` instance for this algorithm. */
  protected def newDigest(): MessageDigest

  /** Thread-local `MessageDigest` for efficient reuse. */
  private lazy val threadLocalDigest: ThreadLocal[MessageDigest] =
    ThreadLocal.withInitial(() => newDigest())

  protected final def getDigest: MessageDigest = threadLocalDigest.get()

  protected final def cloneDigest(digest: MessageDigest): MessageDigest =
    Try(digest.clone().asInstanceOf[MessageDigest]).getOrElse(newDigest())

  final def hash(data: Array[Byte]): String =
    ByteVector(cloneDigest(getDigest).digest(data)).toHex

  final def hex(data: String): String =
    hash(data.getBytes(StandardCharsets.UTF_8))

object HashAlgorithm:

  given default: HashAlgorithm = Blake3

  case object Blake3 extends HashAlgorithm:
    val canonicalName: String                = "blake3"
    val hexDigestLength: Int                 = 64
    protected def newDigest(): MessageDigest = Blake3MessageDigest()

  case object SHA256 extends HashAlgorithm:
    val canonicalName: String                = "sha256"
    val hexDigestLength: Int                 = 64
    protected def newDigest(): MessageDigest =
      MessageDigest.getInstance("SHA-256")

  case object SHA512 extends HashAlgorithm:
    val canonicalName: String                = "sha512"
    val hexDigestLength: Int                 = 128
    protected def newDigest(): MessageDigest =
      MessageDigest.getInstance("SHA-512")

  val values: List[HashAlgorithm] = List(Blake3, SHA256, SHA512)

  private val aliases: Map[String, HashAlgorithm] =
    (values.flatMap { a =>
      val base  = a.canonicalName
      val upper = base.toUpperCase
      val jca   = base match
        case "sha256" => List("SHA-256", "SHA256")
        case "sha512" => List("SHA-512", "SHA512")
        case "blake3" => List("BLAKE3")
        case _        => Nil
      (base :: upper :: jca).map(_ -> a)
    }).toMap

  /** Parse a hash algorithm from a string (canonical, uppercase, or JCA name). */
  def parse(input: String): Either[String, HashAlgorithm] =
    aliases.get(input).toRight(s"Unknown hash algorithm: $input")

  given Schema[HashAlgorithm] = DeriveSchema.gen[HashAlgorithm]

/**
 * MessageDigest wrapper providing a `MessageDigest`-compatible interface for
 * the Blake3 implementation which uses its own API.
 */
private final class Blake3MessageDigest extends MessageDigest("BLAKE3"):
  import io.github.rctcwyvrn.blake3.Blake3

  private var hasher = Blake3.newInstance()

  override def engineUpdate(input: Byte): Unit =
    hasher.update(Array(input)): Unit

  override def engineUpdate(input: Array[Byte], offset: Int, len: Int): Unit =
    val slice = java.util.Arrays.copyOfRange(input, offset, offset + len)
    hasher.update(slice): Unit

  override def engineDigest(): Array[Byte] =
    val bytes = hasher.digest()
    hasher = Blake3.newInstance()
    bytes

  override def engineReset(): Unit =
    hasher = Blake3.newInstance()
