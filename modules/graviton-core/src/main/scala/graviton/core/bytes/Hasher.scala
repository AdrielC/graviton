package graviton.core.bytes

import java.security.{MessageDigest, Provider => JProvider}
import zio.{Chunk, ZIO, ZLayer}
import zio.stream.{ZPipeline, ZSink}
import java.nio.charset.StandardCharsets
import scala.util.Try
import scodec.bits.ByteVector
import graviton.core.keys.KeyBits
import java.util.concurrent.atomic.AtomicLong
import graviton.core.types.BlockSize
import scodec.bits.Bases.Alphabets.HexLowercase

trait Hasher:
  def algo: HashAlgo
  def inputSize: Long
  def update(chunk: Hasher.Digestable): Hasher
  def digest: Either[String, Digest]
  def digestKeyBits: Either[String, KeyBits] =
    digest.flatMap(d => KeyBits.create(algo, d, inputSize))
  def result: Either[String, Digest]         = digest
  def reset: Unit

private[graviton] final class HasherImpl(
  val algo: HashAlgo,
  private val md: MessageDigest,
  val _inputSize: AtomicLong,
) extends Hasher:
  self: HasherImpl =>

  override def inputSize: Long                          = _inputSize.get()
  override def reset: Unit                              = md.reset()
  override def update(chunk: Hasher.Digestable): Hasher =
    chunk match
      case chunk: Chunk[Byte] =>
        val arr = chunk.toArray
        _inputSize.addAndGet(arr.length.toLong)
        md.update(arr)
        self
      case chunk: Array[Byte] =>
        val arr = chunk
        _inputSize.addAndGet(arr.length.toLong)
        md.update(arr)
      case chunk: ByteVector  =>
        val arr = chunk.toArray
        _inputSize.addAndGet(arr.length.toLong)
        md.update(arr)
      case digest: Digest     =>
        val arr = digest.bytes
        _inputSize.addAndGet(arr.length.toLong)
        md.update(arr)
      case s: String          =>
        val arr = s.getBytes(StandardCharsets.UTF_8)
        _inputSize.addAndGet(arr.length.toLong)
        md.update(arr)
    self

  override def digest: Either[String, Digest] =
    Digest.fromBytes(md.digest)

object Hasher:

  type Digestable = ByteVector | Chunk[Byte] | Array[Byte] | String | Digest

  import scala.quoted.*

  given ToExpr[Digestable]   = new ToExpr[Digestable] {
    def apply(value: Digestable)(using Quotes): Expr[Digestable] = value match
      case chunk: Chunk[Byte]     => '{ zio.Chunk.fromArray(${ Expr(chunk.toArray) }) }
      case array: Array[Byte]     => '{ zio.Chunk.fromArray(${ Expr(array) }) }
      case byteVector: ByteVector => '{ zio.Chunk.fromArray(${ Expr(byteVector.toArray) }) }
      case string: String         => '{ zio.Chunk.fromArray(${ Expr(string.getBytes(StandardCharsets.UTF_8)) }) }
      case digest: Digest         => '{ zio.Chunk.fromArray(${ Expr(digest.bytes) }) }
  }
  given FromExpr[Digestable] = new FromExpr[Digestable] {
    def unapply(value: Expr[Digestable])(using Quotes): Option[Digestable] = value match
      case '{ ${ Expr(chunk: Chunk[Byte]) } }     => Some(chunk)
      case '{ ${ Expr(array: Array[Byte]) } }     => Some(array)
      case '{ ${ Expr(byteVector: ByteVector) } } => Some(byteVector)
      case '{ ${ Expr(string: String) } }         => Some(string)
      case '{ ${ Expr(digest: Digest) } }         => Some(digest)
      case _                                      => None
  }

  trait Provider:
    def getInstance(hashAlgo: HashAlgo): Either[String, Hasher]

  object Provider:

    def default(provider: Option[JProvider] = None): Provider = new Provider {
      override def getInstance(hashAlgo: HashAlgo): Either[String, Hasher] =
        instantiate(hashAlgo.primaryName, provider)
          .map(new HasherImpl(hashAlgo, _, new AtomicLong(0L)))
          .left
          .map(err => Option(err).map(_.getMessage).getOrElse("Unknown error"))
    }

    val layer: ZLayer[Any, Nothing, Provider] =
      ZLayer.succeed(default(None))

  private def instantiate(
    name: HashAlgo.AlgoName,
    provider: Option[JProvider],
  ): Either[Throwable, MessageDigest] =
    provider match
      case Some(explicit) => Try(MessageDigest.getInstance(name, explicit)).toEither
      case None           => Try(MessageDigest.getInstance(name)).toEither

  def systemDefault: Either[String, Hasher] =
    Hasher.hasher(HashAlgo.runtimeDefault, None)

  def hasher(algo: HashAlgo, provider: Option[JProvider] = None): Either[String, Hasher] =
    Provider
      .default(provider)
      .getInstance(algo)
      .left
      .map(err => Option(err).map(_.toString).getOrElse("Unknown error"))

  def unsafeMessageDigest(algo: HashAlgo, provider: Option[JProvider] = None): MessageDigest =
    instantiate(algo.primaryName, provider)
      .fold(err => throw new IllegalStateException(err.toString), identity)

  def sink(hasher: Option[Hasher] = None): ZSink[Any, IllegalArgumentException, Byte, Nothing, KeyBits] =
    hasher match
      case Some(h) =>
        ZSink
          .foldLeft((h, 0L)) { (acc, byte: Byte) =>
            val (h, size) = acc
            val _         = h.update(Array(byte))
            (h, size + 1)
          }
          .mapZIO(acc =>
            ZIO.fromEither(
              acc._1.digest
                .flatMap(d => KeyBits.create(acc._1.algo, d, acc._2))
                .left
                .map(err => IllegalArgumentException(Option(err).getOrElse("Unknown error")))
            )
          )

      case None =>
        ZSink.unwrap:
          ZIO
            .fromEither(Hasher.systemDefault)
            .mapError(err => IllegalArgumentException(Option(err).map(_.toString).getOrElse("Unknown error")))
            .map(h => sink(Some(h)))

  def pipeline(multi: Option[MultiHasher] = None): ZPipeline[Any, IllegalArgumentException, Byte, MultiHasher.Results] =
    multi match
      case Some(value) =>
        ZPipeline.mapChunksZIO { (chunk: Chunk[Byte]) =>
          ZIO
            .fromEither(value.update(chunk).results.toEither)
            .map(Chunk.single)
            .mapError(errors => IllegalArgumentException(errors.mkString(", ")))
        }

      case None =>
        ZPipeline.unwrap:
          ZIO
            .fromEither(MultiHasher.Hashers.default)
            .mapError(msg => IllegalArgumentException(Option(msg).getOrElse("Unknown error")))
            .map { value =>
              ZPipeline.mapChunksZIO { (chunk: Chunk[Byte]) =>
                ZIO
                  .fromEither(value.update(chunk).results.toEither)
                  .map(Chunk.single)
                  .mapError(errors => IllegalArgumentException(errors.mkString(", ")))
              }
            }
