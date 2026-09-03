package graviton.bytes

import scodec.bits.ByteVector
import zio.Chunk

import java.nio.charset.StandardCharsets
import scala.annotation.implicitNotFound
import scala.compiletime.{constValue, erasedValue, summonInline}
import scala.deriving.Mirror

/** One immutable byte segment in a canonical cryptographic input. */
opaque type HashSegment = Chunk[Byte]

object HashSegment:
  def apply(bytes: Chunk[Byte]): HashSegment = bytes

  extension (segment: HashSegment) def bytes: Chunk[Byte] = segment

/** Exact byte length of a canonical cryptographic input. */
opaque type HashInputLength <: Long = Long

object HashInputLength:
  import HashSegment.*

  private[bytes] val zero: HashInputLength = 0L

  private[bytes] def fromSegment(segment: HashSegment): HashInputLength =
    segment.bytes.length.toLong

  private[bytes] def add(left: HashInputLength, right: HashInputLength): HashInputLength =
    java.lang.Math.addExact(left, right)

/**
 * A canonical, segmented cryptographic input.
 *
 * Segment boundaries are an encoding implementation detail and do not affect
 * the resulting digest. Consumers receive segments through [[foreachSegment]]
 * so hashing never requires a concatenated payload or an imprecise
 * `Chunk[Chunk[Byte]]` API.
 */
final class HashInput private (
  private val segments: Chunk[HashSegment],
  val byteLength: HashInputLength,
):
  def foreachSegment(consume: HashSegment => Unit): Unit =
    segments.foreach(consume)

  def ++(that: HashInput): HashInput =
    HashInput.concat(Chunk(this, that))

  private[bytes] def materialize: Chunk[Byte] =
    segments.flatMap(_.bytes)

  private[bytes] def segmentCount: Int =
    segments.length

object HashInput:
  val empty: HashInput = new HashInput(Chunk.empty, HashInputLength.zero)

  def bytes(value: Chunk[Byte]): HashInput =
    if value.isEmpty then empty
    else
      val segment = HashSegment(value)
      new HashInput(Chunk.single(segment), HashInputLength.fromSegment(segment))

  def segments(values: Chunk[Byte]*): HashInput =
    concat(Chunk.fromIterable(values).map(bytes))

  def concat(inputs: Iterable[HashInput]): HashInput =
    val segmentBuilder = Chunk.newBuilder[HashSegment]
    var length         = HashInputLength.zero
    inputs.foreach { input =>
      input.foreachSegment(segmentBuilder += _)
      length = HashInputLength.add(length, input.byteLength)
    }
    new HashInput(segmentBuilder.result(), length)

/**
 * A canonical immutable byte representation for cryptographic hashing.
 *
 * Instances must have identical framing and encoding semantics on the JVM,
 * Scala.js, and Scala Native. A [[HashInput]] can expose several strict,
 * immutable segments so a structured value never has to concatenate its full
 * representation.
 */
@implicitNotFound("No cryptographic Hashable instance is available for ${A}")
trait Hashable[-A]:
  self =>

  def input(value: A): HashInput

  final def contramap[B](f: B => A): Hashable[B] =
    value => self.input(f(value))

object Hashable:

  private val ProductDomain = HashInput.bytes(utf8("graviton:hashable:product:v1"))
  private val SumDomain     = HashInput.bytes(utf8("graviton:hashable:sum:v1"))

  def apply[A](using instance: Hashable[A]): Hashable[A] = instance

  def instance[A](f: A => HashInput): Hashable[A] =
    value => f(value)

  given Hashable[Chunk[Byte]] =
    value => HashInput.bytes(value)

  given Hashable[ByteVector] =
    value => HashInput.bytes(Chunk.fromIterable(value.toIterable))

  given Hashable[String] =
    value => HashInput.bytes(utf8(value))

  given Hashable[Byte] =
    value => HashInput.bytes(Chunk.single(value))

  /**
   * Derive a canonical structural encoding for a case class or sealed trait.
   *
   * The encoding is domain-separated and length-delimited. Product type names,
   * field names, field order, and sum alternatives are therefore part of the
   * hash contract. Renaming or reordering them intentionally changes the hash.
   * Every product field must already have a `Hashable` instance.
   *
   * This is Scala `Mirror` derivation. ZIO Prelude's `Derive[F, Typeclass]`
   * instead lifts a typeclass through a unary type constructor and does not
   * derive arbitrary products or sums.
   */
  inline def derived[A](using mirror: Mirror.Of[A]): Hashable[A] =
    inline mirror match
      case product: Mirror.ProductOf[A] => derivedProduct(product)
      case sum: Mirror.SumOf[A]         => derivedSum(sum)

  inline private def derivedProduct[A](mirror: Mirror.ProductOf[A]): Hashable[A] =
    val typeName       = constValue[mirror.MirroredLabel].toString
    val fieldNames     = labels[mirror.MirroredElemLabels]
    val fieldHashables = instances[mirror.MirroredElemTypes]

    instance { value =>
      val product = value.asInstanceOf[Product]
      val output  = Chunk.newBuilder[HashInput]

      output += ProductDomain
      output += text(typeName)
      output += int64(fieldNames.length.toLong)

      var index = 0
      while index < fieldNames.length do
        output += text(fieldNames(index))
        val input = fieldHashables(index).input(product.productElement(index))
        output += int64(input.byteLength)
        output += input
        index += 1

      HashInput.concat(output.result())
    }

  inline private def derivedSum[A](mirror: Mirror.SumOf[A]): Hashable[A] =
    val typeName         = constValue[mirror.MirroredLabel].toString
    val alternativeNames = labels[mirror.MirroredElemLabels]
    val alternatives     = derivedInstances[mirror.MirroredElemTypes]

    instance { value =>
      val ordinal = mirror.ordinal(value)
      val input   = alternatives(ordinal).input(value)
      HashInput.concat(
        Chunk(
          SumDomain,
          text(typeName),
          int64(ordinal.toLong),
          text(alternativeNames(ordinal)),
          int64(input.byteLength),
          input,
        )
      )
    }

  inline private def instances[Elements <: Tuple]: Chunk[Hashable[Any]] =
    inline erasedValue[Elements] match
      case _: EmptyTuple     => Chunk.empty
      case _: (head *: tail) =>
        Chunk.single(summonInline[Hashable[head]].asInstanceOf[Hashable[Any]]) ++ instances[tail]

  inline private def derivedInstances[Elements <: Tuple]: Chunk[Hashable[Any]] =
    inline erasedValue[Elements] match
      case _: EmptyTuple     => Chunk.empty
      case _: (head *: tail) =>
        Chunk.single(derived[head](using summonInline[Mirror.Of[head]]).asInstanceOf[Hashable[Any]]) ++
          derivedInstances[tail]

  inline private def labels[Labels <: Tuple]: Chunk[String] =
    inline erasedValue[Labels] match
      case _: EmptyTuple     => Chunk.empty
      case _: (head *: tail) => Chunk.single(constValue[head].toString) ++ labels[tail]

  private def text(value: String): HashInput =
    val bytes = utf8(value)
    HashInput.concat(Chunk(int64(bytes.length.toLong), HashInput.bytes(bytes)))

  private def int64(value: Long): HashInput =
    HashInput.bytes(
      Chunk(
        (value >>> 56).toByte,
        (value >>> 48).toByte,
        (value >>> 40).toByte,
        (value >>> 32).toByte,
        (value >>> 24).toByte,
        (value >>> 16).toByte,
        (value >>> 8).toByte,
        value.toByte,
      )
    )

  private def utf8(value: String): Chunk[Byte] =
    Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8))
