package graviton.core

import graviton.core.model.{
  ByteConstraints,
  BlockIndex as ModelBlockIndex,
  BlockSize as ModelBlockSize,
  ChunkCount as ModelChunkCount,
  ChunkIndex as ModelChunkIndex,
  FileSize as ModelFileSize,
  UploadChunkSize as ModelChunkSize,
}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.numeric
import zio.schema.Schema
import graviton.core.bytes.Digest
import io.github.iltotore.iron.IronType

import compiletime.ops.int.{`*`}
import compiletime.ops.long.{`*` as `**`}

import java.util.regex.Pattern

import scala.collection.immutable.ListMap
import graviton.core.ranges.DiscreteDomain
import scala.compiletime.ops.int

trait RefinedSubtypeExt[A, C] extends RefinedSubtype[A, C]:

  given (Schema[A]) => zio.schema.Schema[T] = Schema[A].transformOrFail(
    value => either(value),
    refined => Right(refined.value),
  ).annotate((RefinedTypeExtMessage(rtc.message)))

  import scala.quoted.*

  given (f: FromExpr[A]) => FromExpr[T] = new FromExpr[T] {
    def unapply(value: Expr[T])(using Quotes): Option[T] = f.unapply('{ value.valueOrAbort }).flatMap(a => either(a).toOption)
  }
  given (e: ToExpr[A], f: FromExpr[A]) => ToExpr[T] = new ToExpr[T] {
    def apply(value: T)(using Quotes): Expr[T] = '{ 
      e(value).value.flatMap(either(_).toOption).getOrElse(value)
    }
  }

end RefinedSubtypeExt


trait RefinedTypeExt[A, C] extends RefinedType[A, C]:

	given (Schema[A]) => zio.schema.Schema[T] = Schema[A].transformOrFail(
		value => either(value),
		refined => Right(refined.value),
	).annotate((RefinedTypeExtMessage(rtc.message)))

	import scala.quoted.*

	given (f: FromExpr[A]) => FromExpr[T] = new FromExpr[T] {
		def unapply(value: Expr[T])(using Quotes): Option[T] = f.unapply('{ value.valueOrAbort.value }).flatMap(a => either(a).toOption)
	}
	given (e: ToExpr[A], f: FromExpr[A]) => ToExpr[T] = new ToExpr[T] {
		def apply(value: T)(using Quotes): Expr[T] = '{ 
			e(value.value).value.flatMap(either(_).toOption).getOrElse(value)
		}
	}

end RefinedTypeExt

given [K, V] => (K: Schema[K], V: Schema[V]) => Schema[ListMap[K, V]] = Schema
  .map[K, V]
  .transform(
    map => ListMap.from(map.toList),
    listMap => listMap.toMap,
  )

case class RefinedTypeExtMessage(message: String)

transparent inline given [A, B] => (rtc: Constraint[A, B], schema: Schema[A]) => Schema[IronType[A, B]] =
  schema
    .transformOrFail(
      value => value.refineEither[B],
      refined => Right(refined.asInstanceOf[A]),
    )
    .annotate((RefinedTypeExtMessage(rtc.message)))

object types:

  type AlgoConstraint = Match["(sha-256|sha-1|blake3|md5)"]
  type HexLowerConstraint  = Match["[0-9a-f]{1,64}"]
  type HexUpperConstraint  = Match["[0-9A-F]{1,64}"]
  type HexConstraint       = HexLowerConstraint | HexUpperConstraint
  type KekIdConstraint = Match["[A-Za-z0-9:_-]{4,128}"]

  final type TotalMaxT[T] <: T = T match
    case Int => Int.MaxValue.type & T
    case Long => Long.MaxValue.type & T

  final type TotalMinT[T] <: T = T match
    case Int => Int.MinValue.type & T
    case Long => Long.MinValue.type & T


  trait SizeTrait[+Tpe <: Int | Long](using integral: Integral[Tpe], discrete: DiscreteDomain[Tpe]):
    parent =>

    type TotalMax = TotalMaxT[Tpe]
    inline def TotalMax: TotalMax = compiletime.constValue[TotalMaxT[Tpe]]

    type TotalMin = TotalMinT[Tpe]
    inline def TotalMin: TotalMin = compiletime.constValue[TotalMinT[Tpe]]

    type TCConstraint[Mx <: Tpe, Mn <: Tpe] = 
      numeric.GreaterEqual[Mn] & 
      numeric.LessEqual[Mx] & 
      numeric.LessEqual[TotalMax] & 
      numeric.GreaterEqual[TotalMin]

    trait Specialized[
      Mn <: Tpe, 
      Mx <: Tpe, 
      Z <: Tpe, 
      O <: Tpe, 
      TC[mn <: Mn, mx <: Mx, z <: Z, o <: O] <: parent.Trait[mn, mx, z, o]  
    ](using mnV: ValueOf[Mn], mxV: ValueOf[Mx], zV: ValueOf[Z], oV: ValueOf[O]):
      self: Trait[Mn, Mx, Z, O] & RefinedType[Tpe, TCConstraint[Mn, Mx]] =>

        opaque type SubT[t <: Tpe] <: (t & self.T) = self.T
        object SubT:

          final type Max = SubT[Mx]
          val Max: Max = SubT.applyUnsafe(mxV.value)

          final type Min = SubT[Mn]
          val Min: Min = SubT.applyUnsafe(mnV.value)

          final type Zero = SubT[Z]
          val Zero: Zero = SubT.applyUnsafe(zV.value)
          
          final type One = SubT[O]
          val One: One = SubT.applyUnsafe(oV.value)

          inline def applyUnsafe[t <: Tpe](t: t): SubT[t] = self.applyUnsafe(t)

          def either[t <: Tpe](t: t): Either[String, SubT[t]] = 
            self.either(t).map(assume(_))

          extension [t <: Tpe](t: SubT[t]) def value: t = t
        
        end SubT

        export SubT.{
          Max,
          Min,
          Zero,
          One,
        }

    trait Trait[Mn <: Tpe, Mx <: Tpe, Z <: Tpe, O <: Tpe](
      using 
      Constraint[Tpe, GreaterEqual[Z]],
      Constraint[Tpe, LessEqual[Mx]]
    ) extends RefinedSubtypeExt[Tpe, numeric.Greater[Mn] & numeric.LessEqual[Mx]], parent.Specialized[Mn, Mx, Z, O, Trait]: 
      self =>

        given Constraint[T, numeric.Greater[Mn] & numeric.LessEqual[Mx]] = new Constraint[T, numeric.Greater[Mn] & numeric.LessEqual[Mx]]:
          inline def message: String = "value must be between $Mn and $Mx"
          inline def test(inline value: T): Boolean = (value >> Min) && (value << Max)

        given DiscreteDomain[self.T] = new DiscreteDomain[self.T]:
          def next(value: self.T): self.T = value.saturateAdd(self.One)
          def previous(value: self.T): self.T = value.saturateSub(self.One)

        given Integral[self.T] = new Integral[self.T]:
          def fromInt(n: Int): self.T = self.either(integral.fromInt(n)).toOption.getOrElse(Max)
          def toInt(n: self.T): Int = integral.toInt(n.value)
          def compare(x: self.T, y: self.T): Int = integral.compare(x, y)
          def plus(x: self.T, y: self.T): self.T = x.saturateAdd(y)
          def minus(x: self.T, y: self.T): self.T = x.saturateSub(y)
          def times(x: self.T, y: self.T): self.T = x.saturateMul(y)
          def negate(x: self.T): self.T = self.Zero
        
        extension (value: self.T)

          inline def increment(n: Int :| (numeric.GreaterEqual[0])): Either[String, self.T] =
            either(integral.fromInt(n)).map:
              case nT => value.saturateMul(nT)
          
          infix def >==(other: self.T): Boolean = integral.gteq(value, other)
          infix def >>(other: self.T): Boolean = integral.gt(value, other)
          infix def <==(other: self.T): Boolean = integral.lteq(value, other)
          infix def <<(other: self.T): Boolean = integral.lt(value, other)
          def next: Option[self.T] = option(discrete.next(value))
          def previous: Option[self.T] = option(discrete.previous(value))
          def saturateAdd(other: self.T): self.T = option(integral.plus(value, other)).getOrElse(Max)
          def saturateSub(other: self.T): self.T = option(integral.minus(value, other)).getOrElse(Min)
          def saturateMul(other: self.T): self.T = option(integral.times(value, other)).getOrElse(Max)
        end extension
    end Trait

  end SizeTrait

  trait IntSizeTrait[N <: Int: {Integral, DiscreteDomain}] extends SizeTrait[N]

  trait LongSizeTrait[N <: Long: {Integral, DiscreteDomain}] extends SizeTrait[N]
  
  object SizeTraitInt extends IntSizeTrait[Int]
  object SizeTraitLong extends LongSizeTrait[Long]
  
  type SizeLong = SizeLong.T
  object SizeLong extends SizeTraitLong.Trait[0, Long.MaxValue.type, 0, 1]:
  
    given [N <: Long] => (c: Constraint[Long, numeric.Greater[Min] & numeric.LessEqual[Max]]) => Constraint[T, numeric.Greater[Min] & numeric.LessEqual[Max]] =
      new Constraint[T, numeric.Greater[Min] & numeric.LessEqual[Max]]:
        inline def message: String = c.message
        inline def test(inline value: T): Boolean = c.test(value)

  
  type Size = Size.T
  object Size extends SizeTraitInt.Trait[0, Int.MaxValue.type, 0, 1]:
  
    given [N <: T] => (c: Constraint[Int, numeric.Greater[Min] & numeric.LessEqual[Max]]) => Constraint[T, numeric.Greater[Min] & numeric.LessEqual[Max]] =
      new Constraint[T, numeric.Greater[Min] & numeric.LessEqual[Max]]:
        inline def message: String = c.message
        inline def test(inline value: T): Boolean = c.test(value.value)

  // 16 MiB
  type MaxBlockBytes = 16 * 1024 * 1024

  object SizeSubtype extends IntSizeTrait[Size]
  object SizeLongSubtype extends LongSizeTrait[Size]

  type BlockSize = BlockSize.T
  object BlockSize extends SizeSubtype.Trait[1, MaxBlockBytes, 0, 1]

  type FileSize = FileSize.T
  object FileSize extends SizeLongSubtype.Trait[0L, 1024L ** 4L, 0L, 1L]


  type Algo = Algo.T
  object Algo extends RefinedTypeExt[String, Match["(sha-256|sha-1|blake3|md5)"]]

  trait HexTrait[C <: Match[? <: String]] extends RefinedTypeExt[String, C]:
    self: HexTrait[C] =>

      extension (value: self.T)
        def length: Int = value.length

  
  type HexString = HexString.T
  object HexString extends HexTrait[HexConstraint]
  type HexLower = HexLower.T
  object HexLower extends HexTrait[HexLowerConstraint]
  type HexUpper = HexUpper.T
  object HexUpper extends HexTrait[HexUpperConstraint]
    
  type BlockIndex       = BlockIndex.T
  object BlockIndex extends SizeLongSubtype.Trait[0L, Long.MaxValue.type, 0L, 1L]
  type CompressionLevel = CompressionLevel.T
  object CompressionLevel extends SizeSubtype.Trait[-1, 22, 0, 1]
  type KekId            = KekId.T
  object KekId extends HexTrait[KekIdConstraint]
  type NonceLength      = NonceLength.T
  object NonceLength extends SizeTraitInt.Trait[0, 32, 0, 1]
  type LocatorScheme    = LocatorScheme.T
  object LocatorScheme extends RefinedTypeExt[String, Match["[a-z0-9+.-]+"]]
  type PathSegment      = PathSegment.T
  object PathSegment extends RefinedTypeExt[String, Match["[^/]+"] & MinLength[1]]
  type FileSegment      = FileSegment.T
  object FileSegment extends RefinedTypeExt[String, Match["[^/]+"] & MinLength[1]]
  type ChunkCount      = ChunkCount.T
  object ChunkCount extends SizeLongSubtype.Trait[0L, Long.MaxValue.type, 0L, 1L]

  val MaxBlockBytes: Int = ByteConstraints.MaxBlockBytes

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
      case "blake3"  => Right(())
      case other     => Left(s"Unknown digest algorithm: $other")

  given Schema[Size] =
    Schema[Long].transformOrFail(
      value =>
        if value >= 0 then Right(value.asInstanceOf[Size])
        else Left(s"Size must be ? 0, got $value"),
      refined => Right(refined.asInstanceOf[Long]),
    )


  given Schema[CompressionLevel] =
    Schema[Int].transformOrFail(
      value =>
        if value < -1 || value > 22 then Left(s"Compression level must be between -1 and 22, got $value")
        else Right(value.asInstanceOf[CompressionLevel]),
      refined => Right(refined.asInstanceOf[Int]),
    )

  given Schema[KekId] =
    Schema[String].transformOrFail(
      value =>
        if KekIdPattern.matcher(value).matches() then Right(value.asInstanceOf[KekId])
        else Left("KEK identifier must match [A-Za-z0-9:_-]{4,128}"),
      refined => Right(refined.asInstanceOf[String]),
    )

  given Schema[NonceLength] =
    Schema[Int].transformOrFail(
      value =>
        if value <= 0 || value > 32 then Left(s"Nonce length must be between 1 and 32, got $value")
        else Right(value.asInstanceOf[NonceLength]),
      refined => Right(refined.asInstanceOf[Int]),
    )
