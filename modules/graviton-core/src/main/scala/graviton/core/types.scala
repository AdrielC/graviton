package graviton.core

import graviton.core.model.ByteConstraints
import graviton.core.ranges.DiscreteDomain
import io.github.iltotore.iron.*
import io.github.iltotore.iron.IronType
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.numeric
import zio.schema.Schema

import java.util.regex.Pattern
import scala.collection.immutable.ListMap
import scala.compiletime

// ---------------------------
// ZIO Schema + Iron glue
// ---------------------------

case class RefinedTypeExtMessage(message: String)

trait RefinedSubtypeExt[A, C] extends RefinedSubtype[A, C]:
  given (Schema[A]) => Schema[T] =
    Schema[A]
      .transformOrFail(either(_), r => Right(r.value))
      .annotate(RefinedTypeExtMessage(rtc.message))

trait RefinedTypeExt[A, C] extends RefinedType[A, C]:
  given (Schema[A]) => Schema[T] =
    Schema[A]
      .transformOrFail(either(_), r => Right(r.value))
      .annotate(RefinedTypeExtMessage(rtc.message))

transparent inline given [A, B](using rtc: Constraint[A, B], schema: Schema[A]): Schema[IronType[A, B]] =
  schema
    .transformOrFail(
      value => value.refineEither[B].left.map(_ => rtc.message),
      refined => Right(refined.asInstanceOf[A]),
    )
    .annotate(RefinedTypeExtMessage(rtc.message))

given [K: Schema, V: Schema]: Schema[ListMap[K, V]] =
  Schema
    .map[K, V]
    .transform(m => ListMap.from(m), lm => lm.toMap)

// ---------------------------
// Types
// ---------------------------

object types:

  // --- String constraints
  type AlgoConstraint     = Match["(sha-256|sha-1|blake3|md5)"]
  type HexLowerConstraint = Match["[0-9a-f]{1,64}"]
  type HexUpperConstraint = Match["[0-9A-F]{1,64}"]
  type HexConstraint      = HexLowerConstraint | HexUpperConstraint
  type KekIdConstraint    = Match["[A-Za-z0-9:_-]{4,128}"]

  // --- total bounds (compile-time)
  final type TotalMaxT[T] <: T = T match
    case Int  => Int.MaxValue.type & T
    case Long => Long.MaxValue.type & T

  final type TotalMinT[T] <: T = T match
    case Int  => Int.MinValue.type & T
    case Long => Long.MinValue.type & T

  // ---------------------------
  // Size traits (clever, but safe)
  // ---------------------------

  trait SizeTrait[Tpe <: Int | Long](using integral: Integral[Tpe], discrete: DiscreteDomain[Tpe]):
    type TotalMax = TotalMaxT[Tpe]
    inline def TotalMax: TotalMax = compiletime.constValue[TotalMaxT[Tpe]]

    type TotalMin = TotalMinT[Tpe]
    inline def TotalMin: TotalMin = compiletime.constValue[TotalMinT[Tpe]]

    trait Trait[Mn <: Tpe, Mx <: Tpe, Z <: Tpe, O <: Tpe](
      using mnV: ValueOf[Mn],
      mxV: ValueOf[Mx],
      zV: ValueOf[Z],
      oV: ValueOf[O],
    ) extends RefinedTypeExt[Tpe, numeric.GreaterEqual[Mn] & numeric.LessEqual[Mx]]:
      self =>

      type Max  = self.T
      type Min  = self.T
      type Zero = self.T
      type One  = self.T

      // Stable value-level bounds for this refined type.
      val Max: self.T  = self.applyUnsafe(mxV.value)
      val Min: self.T  = self.applyUnsafe(mnV.value)
      // Note: Some refined families (e.g. sizes/counts) forbid 0, so "Zero" may not exist.
      // We still provide a total value by failing closed to Min.
      val Zero: self.T = self.either(zV.value).fold(_ => Min, identity)
      val One: self.T  = self.either(oV.value).fold(_ => Min, identity)

      inline def unsafe(t: Tpe): self.T =
        self.applyUnsafe(t)

      def eitherT(t: Tpe): Either[String, self.T] =
        self.either(t)

      given DiscreteDomain[self.T] with
        def next(v: self.T): self.T =
          option(integral.plus(v.value, One.value)).getOrElse(v)

        def previous(v: self.T): self.T =
          option(integral.minus(v.value, One.value)).getOrElse(v)

      given Integral[self.T] with
        def fromInt(n: Int): self.T =
          self.either(integral.fromInt(n)).fold(_ => Zero, identity) // fail closed-ish

        def parseString(str: String): Option[self.T] =
          integral.parseString(str).flatMap(option)

        def toInt(n: self.T): Int       = integral.toInt(n.value)
        def toLong(n: self.T): Long     = integral.toLong(n.value)
        def toFloat(n: self.T): Float   = integral.toFloat(n.value)
        def toDouble(n: self.T): Double = integral.toDouble(n.value)

        def compare(x: self.T, y: self.T): Int =
          integral.compare(x.value, y.value)

        def plus(x: self.T, y: self.T): self.T =
          // fail closed: if out of range, clamp to Max is not allowed; return x (caller can use checkedAdd)
          option(integral.plus(x.value, y.value)).getOrElse(x)

        def minus(x: self.T, y: self.T): self.T =
          option(integral.minus(x.value, y.value)).getOrElse(x)

        def times(x: self.T, y: self.T): self.T =
          option(integral.times(x.value, y.value)).getOrElse(x)

        def negate(x: self.T): self.T =
          option(integral.negate(x.value)).getOrElse(Zero)

        def quot(x: self.T, y: self.T): self.T =
          if y.value == integral.zero then Zero
          else option(integral.quot(x.value, y.value)).getOrElse(Zero)

        def rem(x: self.T, y: self.T): self.T =
          if y.value == integral.zero then Zero
          else option(integral.rem(x.value, y.value)).getOrElse(Zero)

      extension (value: self.T)

        // increment means add, not multiply
        inline def increment(n: Int :| numeric.GreaterEqual[0]): Either[String, self.T] =
          self.either(integral.plus(value.value, integral.fromInt(n)))

        infix def >==(other: self.T): Boolean = integral.gteq(value.value, other.value)
        infix def <==(other: self.T): Boolean = integral.lteq(value.value, other.value)
        infix def gt(other: self.T): Boolean  = integral.gt(value.value, other.value)
        infix def lt(other: self.T): Boolean  = integral.lt(value.value, other.value)

        def next: Option[self.T] =
          option(integral.plus(value.value, One.value))

        def previous: Option[self.T] =
          option(integral.minus(value.value, One.value))

        // explicit checked ops (no saturation)
        def checkedAdd(other: self.T): Either[String, self.T] =
          self.either(integral.plus(value.value, other.value))

        def checkedSub(other: self.T): Either[String, self.T] =
          self.either(integral.minus(value.value, other.value))

        def checkedMul(other: self.T): Either[String, self.T] =
          self.either(integral.times(value.value, other.value))

  end SizeTrait

  trait IntSizeTrait[N <: Int: {Integral, DiscreteDomain}]   extends SizeTrait[N]
  trait LongSizeTrait[N <: Long: {Integral, DiscreteDomain}] extends SizeTrait[N]

  object SizeTraitInt  extends IntSizeTrait[Int]
  object SizeTraitLong extends LongSizeTrait[Long]

  // ---------------------------
  // Base families
  //
  // Law:
  // - Indexes are 0-based (min = 0)
  // - Sizes/counts/bytes are 1-based (min = 1)
  // ---------------------------

  trait Size1 extends SizeTraitInt.Trait[1, Int.MaxValue.type, 0, 1]
  object Size extends Size1
  type Size = Size.T

  trait SizeLong1 extends SizeTraitLong.Trait[1L, Long.MaxValue.type, 0L, 1L]
  object SizeLong extends SizeLong1
  type SizeLong = SizeLong.T

  trait IndexLong0 extends SizeTraitLong.Trait[0L, Long.MaxValue.type, 0L, 1L]

  object SizeSubtype     extends IntSizeTrait[Int]
  object SizeLongSubtype extends LongSizeTrait[Long]

  // Keep this value available for runtime checks.
  val MaxBlockBytes: Int = 16 * 1024 * 1024 // 16 MiB

  type BlockSize = BlockSize.T
  object BlockSize extends SizeSubtype.Trait[1, 16777216, 0, 1] // 16 MiB

  type FileSize = FileSize.T
  object FileSize extends SizeLongSubtype.Trait[1L, 1099511627776L, 0L, 1L] // 1 TiB

  type Algo = Algo.T
  object Algo extends RefinedTypeExt[String, AlgoConstraint]

  trait HexTrait[C <: Match[? <: String]] extends RefinedTypeExt[String, C]:
    extension (value: T) def length: Int = value.value.length

  type HexString = HexString.T
  object HexString extends HexTrait[HexConstraint]
  type HexLower = HexLower.T
  object HexLower extends HexTrait[HexLowerConstraint]:
    type Constraint = HexLowerConstraint
  type HexUpper = HexUpper.T
  object HexUpper extends HexTrait[HexUpperConstraint]:
    type Constraint = HexUpperConstraint

  type BlockIndex = BlockIndex.T
  object BlockIndex extends IndexLong0

  type CompressionLevel = CompressionLevel.T
  object CompressionLevel extends SizeSubtype.Trait[-1, 22, 0, 1]

  type KekId = KekId.T
  object KekId extends HexTrait[KekIdConstraint]

  type NonceLength = NonceLength.T
  object NonceLength extends SizeTraitInt.Trait[1, 32, 0, 1]

  type LocatorScheme = LocatorScheme.T
  object LocatorScheme extends RefinedTypeExt[String, Match["[a-z0-9+.-]+"]]

  type PathSegment = PathSegment.T
  object PathSegment extends RefinedTypeExt[String, Match["[^/]+"] & MinLength[1]]

  type FileSegment = FileSegment.T
  object FileSegment extends RefinedTypeExt[String, Match["[^/]+"] & MinLength[1]]

  type ChunkCount = ChunkCount.T
  object ChunkCount extends SizeLong1

  // MIME type (very light validation; tighten if/when a stricter policy is required).
  type Mime = Mime.T
  object Mime extends RefinedTypeExt[String, MinLength[1] & MaxLength[256]]

  // ---------------------------
  // Digest validation
  // ---------------------------

  private val Sha256HexLength = 64
  private val Sha1HexLength   = 40
  private val Md5HexLength    = 32
  private val KekIdPattern    = Pattern.compile("^[A-Za-z0-9:_-]{4,128}$")

  def validateDigest(algo: Algo, hex: HexLower): Either[String, Unit] =
    algo.value match
      case "sha-256" =>
        Either.cond(hex.length == Sha256HexLength, (), s"sha-256 requires $Sha256HexLength hex chars, got ${hex.length}")
      case "sha-1"   =>
        Either.cond(hex.length == Sha1HexLength, (), s"sha-1 requires $Sha1HexLength hex chars, got ${hex.length}")
      case "md5"     =>
        Either.cond(hex.length == Md5HexLength, (), s"md5 requires $Md5HexLength hex chars, got ${hex.length}")
      case "blake3"  =>
        // decide a policy; safest default is 64 unless you explicitly support variable-length
        Either.cond(hex.length == Sha256HexLength, (), s"blake3 requires $Sha256HexLength hex chars (policy), got ${hex.length}")
      case other     =>
        Left(s"Unknown digest algorithm: $other")
