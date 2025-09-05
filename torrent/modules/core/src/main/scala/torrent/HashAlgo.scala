package torrent

import java.security.MessageDigest

import scala.compiletime.ops.int.+ as ++
import scala.compiletime.ops.string.{ +, Length as StrLength }
import scala.deriving.Mirror
import scala.util.control.NonFatal

import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.{ zio as _, * }
import org.apache.commons.codec.digest.DigestUtils
import torrent.utils.StringUtils.normalize

import zio.json.ast.Json.Str
import zio.json.{ JsonDecoder, JsonEncoder }
import zio.prelude.Debug
import zio.schema.codec.{ BinaryCodec, JsonCodec }
import zio.schema.{ DeriveSchema, Schema }
import zio.{ FiberRef, Scope, UIO, Unsafe, ZEnvironment }

type HashAlgo = HashAlgorithm[? <: String, ? <: Int]
val HashAlgo = HashAlgorithm

@zio.schema.annotation.simpleEnum
enum HashAlgorithm[Name <: (String & Singleton): ValueOf, N <: Int: ValueOf]:
  self =>

  case Blake3   extends HashAlgorithm["blake3", 128]
  case SHA256   extends HashAlgorithm["sha256", 64]
  case SHA512   extends HashAlgorithm["sha512", 128]
  case MD5      extends HashAlgorithm["md5", 32]
  case SHA1     extends HashAlgorithm["sha1", 40]
  case SHA3_256 extends HashAlgorithm["sha3-256", 64]
  case SHA3_512 extends HashAlgorithm["sha3-512", 128]

  val canonicalName: Name       = valueOf[Name]
  override def toString: String = normalize(name)
  def name: Name                = valueOf[Name]
  def digestLength: N           = valueOf[N]
  def keyLength: KeyLength      = (canonicalName.length + 1 + digestLength).asInstanceOf[KeyLength]

  opaque type KeyLength <: Int = StrLength[Name] ++ 1 ++ N

  opaque type NN <: String = Name

  opaque type Digest <: MessageDigest = MessageDigest

  type KeyConstraint = DescribedAs[
    Match["^" + Name + ":[0-9a-f]{N}+$"] & FixedLength[KeyLength],
    "Must be string of format: '<" + Name + ">:<[0-9a-f]{N}+>'"
  ]

  opaque type DigestImpl <: HashAlgorithm[? <: String, ? <: Int] = HashAlgorithm[? <: String, ? <: Int]

  opaque type Key <: String :| KeyConstraint = String :| KeyConstraint

  object Key:
    def apply(hash: Digest): Either[Throwable, BinaryKey.Hashed] =
      try
        BinaryKey
          .Hash(hash)
          .map(s => BinaryKey.Hashed(s, self))
          .left
          .map {
            case e: Throwable => e
            case m: String    => new RuntimeException(m)
          }
      catch case NonFatal(e) => Left(e)

    given Debug[Key] =
      Debug.make(value =>
        Debug.Repr.KeyValue(Debug.Repr.String(canonicalName),
                            Debug.Repr.String(value.substring(canonicalName.length + 1))
        )
      )
  end Key

  def getInstance: self.Digest = self match
    case HashAlgo.Blake3   => new Blake3MessageDigest() // Blake3 has a different API, so we'll create a wrapper
    case HashAlgo.SHA256   => DigestUtils.getSha256Digest()
    case HashAlgo.SHA512   => DigestUtils.getSha512Digest()
    case HashAlgo.MD5      => DigestUtils.getMd5Digest()
    case HashAlgo.SHA1     => DigestUtils.getSha1Digest()
    case HashAlgo.SHA3_256 => DigestUtils.getSha3_256Digest()
    case HashAlgo.SHA3_512 => DigestUtils.getSha3_512Digest()

  /**
   * Compute hash of byte array using the most efficient method for each
   * algorithm
   */
  def hash(data: Array[Byte]): String =
    this match
      case HashAlgo.Blake3   => pt.kcry.blake3.Blake3.newHasher().update(data).doneHex(64)
      case HashAlgo.SHA256   => DigestUtils.sha256Hex(data)
      case HashAlgo.SHA512   => DigestUtils.sha512Hex(data)
      case HashAlgo.MD5      => DigestUtils.md5Hex(data)
      case HashAlgo.SHA1     => DigestUtils.sha1Hex(data)
      case HashAlgo.SHA3_256 => DigestUtils.sha3_256Hex(data)
      case HashAlgo.SHA3_512 => DigestUtils.sha3_512Hex(data)

  /**
   * Compute hash of string using the most efficient method for each algorithm
   */
  def hash(data: String): String =
    this match
      case HashAlgo.Blake3   => hash(data.getBytes("UTF-8"))
      case HashAlgo.SHA256   => DigestUtils.sha256Hex(data)
      case HashAlgo.SHA512   => DigestUtils.sha512Hex(data)
      case HashAlgo.MD5      => DigestUtils.md5Hex(data)
      case HashAlgo.SHA1     => DigestUtils.sha1Hex(data)
      case HashAlgo.SHA3_256 => DigestUtils.sha3_256Hex(data)
      case HashAlgo.SHA3_512 => DigestUtils.sha3_512Hex(data)

/**
 * MessageDigest wrapper for Blake3 to maintain API compatibility
 */
private class Blake3MessageDigest extends MessageDigest("BLAKE3"):
  import pt.kcry.blake3.Blake3

  private var hasher = Blake3.newHasher()

  override def engineUpdate(input: Byte): Unit =
    hasher.update(Array(input)): Unit

  override def engineUpdate(input: Array[Byte], offset: Int, len: Int): Unit =
    val slice = java.util.Arrays.copyOfRange(input, offset, offset + len)
    hasher.update(slice): Unit

  override def engineDigest(): Array[Byte] =
    // Blake3 outputs hex, so we need to convert to bytes
    val hexResult = hasher.doneHex(64)
    val result    = hexResult.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
    hasher = Blake3.newHasher() // Reset for next use
    result

  override def engineReset(): Unit =
    hasher = Blake3.newHasher()

object HashAlgorithm:

  object ref:

    def get: UIO[HashAlgo] =
      fiberRef.get

    val fiberRef: FiberRef[HashAlgo] = Unsafe.unsafe { implicit u =>
      FiberRef.unsafe.make[HashAlgo](HashAlgo.Blake3)
    }

  given schema[Name <: (String & Singleton), N <: Int]: Schema[HashAlgorithm[Name, N]] =
    DeriveSchema.gen[HashAlgorithm[Name, N]]

  given jsonDecoder[Name <: (String & Singleton), N <: Int]: JsonDecoder[HashAlgorithm[Name, N]] =
    JsonCodec.jsonDecoder(Schema[HashAlgorithm[Name, N]])

  given [Name <: (String & Singleton), N <: Int]: JsonEncoder[HashAlgorithm[Name, N]] =
    JsonCodec.jsonEncoder(Schema[HashAlgorithm[Name, N]])

  private def aliases: Map[String, HashAlgo] =
    // get all the values of the sum type
    HashAlgorithm.values.flatMap { case a =>
      (a.canonicalName, a) :: (normalize(a.canonicalName), a) :: Nil
    }.toMap

  given jsonCodec[Name <: (String & Singleton), N <: Int]: BinaryCodec[HashAlgorithm[Name, N]] =
    JsonCodec.schemaBasedBinaryCodec(using
      schema[Name, N]
    )

  def parse(input: String): Either[String, HashAlgo] =
    val normalized = normalize(input)
    aliases.get(normalized) match
      case Some(algo) => Right(algo)
      case None       => Left(s"Unknown hash algorithm: $input")

  /**
   * Convenience methods using Apache Commons Codec
   */
  object Utils:
    def md5Hex(data: Array[Byte]): String = DigestUtils.md5Hex(data)
    def md5Hex(data: String): String      = DigestUtils.md5Hex(data)

    def sha1Hex(data: Array[Byte]): String = DigestUtils.sha1Hex(data)
    def sha1Hex(data: String): String      = DigestUtils.sha1Hex(data)

    def sha256Hex(data: Array[Byte]): String = DigestUtils.sha256Hex(data)
    def sha256Hex(data: String): String      = DigestUtils.sha256Hex(data)

    def sha512Hex(data: Array[Byte]): String = DigestUtils.sha512Hex(data)
    def sha512Hex(data: String): String      = DigestUtils.sha512Hex(data)

    def sha3_256Hex(data: Array[Byte]): String = DigestUtils.sha3_256Hex(data)
    def sha3_256Hex(data: String): String      = DigestUtils.sha3_256Hex(data)

    def sha3_512Hex(data: Array[Byte]): String = DigestUtils.sha3_512Hex(data)
    def sha3_512Hex(data: String): String      = DigestUtils.sha3_512Hex(data)

    def blake3Hex(data: Array[Byte]): String = HashAlgo.Blake3.hash(data)
    def blake3Hex(data: String): String      = HashAlgo.Blake3.hash(data)

@main def testHashAlgo() =
  val algo   = HashAlgo.SHA256
  val data   = "Hello, world!"
  val hash   = algo.hash(data)
  val digest = algo.getInstance
  digest.update(data.getBytes("UTF-8"))
  val key    = algo.Key(digest)
  println(hash)
  println(key)
  println(algo.keyLength)
  println(key.fold(_ => "<error>", _.renderKey))
