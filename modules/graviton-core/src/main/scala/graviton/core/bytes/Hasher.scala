package graviton.core.bytes

import java.security.{MessageDigest, Provider => JProvider}
import zio.{Chunk, IO, ZIO, ZLayer}
import zio.stream.{ZPipeline, ZSink}
import scala.util.Try
import scodec.bits.ByteVector
import graviton.core.keys.KeyBits
import java.util.concurrent.atomic.AtomicLong
import scala.annotation.targetName

trait Hasher:
  def algo: HashAlgo
  def inputSize: Long
  @targetName("updateHashable")
  final def update[A: Hashable](value: A): Hasher =
    Hashable[A].input(value).foreachSegment { segment =>
      val _ = updateChunk(segment.bytes)
    }
    this

  private[bytes] def updateChunk(chunk: Chunk[Byte]): Hasher =
    updateLegacy(chunk)

  @targetName("update")
  def updateLegacy(value: Hasher.Digestable): Hasher

  def hash: Either[HashError, Hash]            =
    digest.left
      .map(detail => HashError.AlgorithmUnavailable(algo, detail))
      .flatMap(HashBytes.fromDigest)
      .flatMap(Hash.make(algo, _))
  def hashed: Either[HashError, HashedContent] =
    val observedSize = inputSize
    hash.flatMap(HashedContent.fromObserved(_, observedSize))
  def digest: Either[String, Digest]
  def digestKeyBits: Either[String, KeyBits]   =
    hashed.map(KeyBits.fromHashed).left.map(_.message)
  def result: Either[String, Digest]           = digest
  def reset: Unit

private[graviton] final class HasherImpl(
  val algo: HashAlgo,
  private val md: MessageDigest,
  val _inputSize: AtomicLong,
) extends Hasher:
  self: HasherImpl =>

  override def inputSize: Long = _inputSize.get()
  override def reset: Unit     = self.synchronized {
    md.reset()
    _inputSize.set(0L)
  }

  @targetName("update")
  override def updateLegacy(value: Hasher.Digestable): Hasher =
    value match
      case chunk: Chunk[Byte] => update(chunk)
      case bytes: ByteVector  => update(bytes)
      case string: String     => update(string)

  override private[bytes] def updateChunk(chunk: Chunk[Byte]): Hasher = self.synchronized {
    _inputSize.addAndGet(chunk.length.toLong)
    // MessageDigest is the private JDK interop boundary. Arrays never escape it.
    md.update(chunk.toArray)
    self
  }

  override def hash: Either[HashError, Hash] =
    hashed.map(_.hash)

  override def hashed: Either[HashError, HashedContent] = self.synchronized {
    val observedSize = _inputSize.getAndSet(0L)
    Hash
      .fromJdkBytes(algo, md.digest())
      .flatMap(HashedContent.fromObserved(_, observedSize))
  }

  override def digest: Either[String, Digest] =
    hash.map(_.bytes).left.map(_.message)

object Hasher:

  type Digestable = ByteVector | Chunk[Byte] | String

  import scala.quoted.*

  given ToExpr[Digestable]   = new ToExpr[Digestable] {
    def apply(value: Digestable)(using Quotes): Expr[Digestable] = value match
      case chunk: Chunk[Byte]     =>
        val bytes = Expr.ofSeq(chunk.map(Expr(_)))
        '{ zio.Chunk.fromIterable($bytes) }
      case byteVector: ByteVector =>
        val bytes = Expr.ofSeq(byteVector.toIterable.map(Expr(_)).toSeq)
        '{ zio.Chunk.fromIterable($bytes) }
      case string: String         => Expr(string)
  }
  given FromExpr[Digestable] = new FromExpr[Digestable] {
    def unapply(value: Expr[Digestable])(using Quotes): Option[Digestable] = value match
      case '{ ${ Expr(chunk: Chunk[Byte]) } }     => Some(chunk)
      case '{ ${ Expr(byteVector: ByteVector) } } => Some(byteVector)
      case '{ ${ Expr(string: String) } }         => Some(string)
      case _                                      => None
  }

  trait Provider:
    def make(hashAlgo: HashAlgo): IO[HashError, Hasher]

  object Provider:

    def make(hashAlgo: HashAlgo): ZIO[Provider, HashError, Hasher] =
      ZIO.serviceWithZIO[Provider](_.make(hashAlgo))

    def default(provider: Option[JProvider] = None): Provider = new Provider {
      override def make(hashAlgo: HashAlgo): IO[HashError, Hasher] =
        ZIO
          .fromEither(instantiate(hashAlgo.primaryName, provider))
          .map(new HasherImpl(hashAlgo, _, new AtomicLong(0L)))
          .mapError(error =>
            HashError.AlgorithmUnavailable(
              hashAlgo,
              Option(error.getMessage).getOrElse(error.getClass.getName),
            )
          )
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
    instantiate(algo.primaryName, provider)
      .map(new HasherImpl(algo, _, new AtomicLong(0L)))
      .left
      .map(error => Option(error.getMessage).getOrElse(error.getClass.getName))

  def make(algo: HashAlgo, provider: Option[JProvider] = None): Either[HashError, Hasher] =
    hasher(algo, provider).left.map(HashError.AlgorithmUnavailable(algo, _))

  def unsafeMessageDigest(algo: HashAlgo, provider: Option[JProvider] = None): MessageDigest =
    instantiate(algo.primaryName, provider)
      .fold(err => throw new IllegalStateException(err.toString), identity)

  def sink(hasher: Option[Hasher] = None): ZSink[Any, IllegalArgumentException, Byte, Nothing, KeyBits] =
    hasher match
      case Some(h) =>
        ZSink
          .foldLeftChunks(h) { (hasher: Hasher, bytes: Chunk[Byte]) =>
            hasher.update(bytes)
          }
          .mapZIO(hasher =>
            ZIO.fromEither(
              hasher.hashed.left
                .map(_.message)
                .map(KeyBits.fromHashed)
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
