package graviton

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import zio.schema.{DeriveSchema, Schema}

/** Supported hashing algorithms for content addressed storage. Each algorithm
  * exposes metadata about its digest and can compute hashes directly.
  */
sealed trait HashAlgorithm:
  /** Canonical lowercase name of the algorithm. */
  def canonicalName: String

  /** Number of hex characters produced by this algorithm. */
  def digestLength: Int

  /** Obtain a new `MessageDigest` instance for this algorithm. */
  protected def newDigest(): MessageDigest

  /** Compute the digest for the provided data and return a lowercase hex
    * string.
    */
  final def hash(data: Array[Byte]): String =
    val md = newDigest()
    val dig = md.digest(data)
    val sb = new StringBuilder(dig.length * 2)
    dig.foreach(b => sb.append(f"$b%02x"))
    sb.toString

  final def hash(data: String): String =
    hash(data.getBytes(StandardCharsets.UTF_8))

object HashAlgorithm:
  case object Blake3 extends HashAlgorithm:
    val canonicalName = "blake3"
    val digestLength = 64
    protected def newDigest(): MessageDigest = Blake3MessageDigest()

  case object SHA256 extends HashAlgorithm:
    val canonicalName = "sha256"
    val digestLength = 64
    protected def newDigest(): MessageDigest =
      MessageDigest.getInstance("SHA-256")

  case object SHA512 extends HashAlgorithm:
    val canonicalName = "sha512"
    val digestLength = 128
    protected def newDigest(): MessageDigest =
      MessageDigest.getInstance("SHA-512")

  val values: List[HashAlgorithm] = List(Blake3, SHA256, SHA512)

  private val aliases: Map[String, HashAlgorithm] =
    values
      .flatMap(a =>
        List(a.canonicalName -> a, a.canonicalName.toUpperCase -> a)
      )
      .toMap

  /** Parse a hash algorithm from a string, accepting canonical and uppercase
    * names.
    */
  def parse(input: String): Either[String, HashAlgorithm] =
    aliases.get(input).toRight(s"Unknown hash algorithm: $input")

  given Schema[HashAlgorithm] = DeriveSchema.gen[HashAlgorithm]

/** MessageDigest wrapper providing a `MessageDigest`-compatible interface for
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
