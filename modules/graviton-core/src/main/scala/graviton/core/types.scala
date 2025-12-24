package graviton.core

import io.github.iltotore.iron.*
import io.github.iltotore.iron.IronType
import io.github.iltotore.iron.constraint.all.*
import zio.schema.Schema

import scala.collection.immutable.ListMap

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

// ---------------------------
// Types
// ---------------------------

object types:

  // --- ZIO Schema support for Iron refined types
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
  // "Cool stuff" size helpers (compile-safe)
  // ---------------------------
  //
  // The earlier “clever” SizeTrait design tried to *also* provide full Numeric/Integral instances
  // for refined types, plus some aggressive self-type constraints. Scala 3 + Iron makes that easy
  // to accidentally break (and it broke the build).
  //
  // This is a compile-safe version that keeps the useful bits:
  // - stable Min/Max/Zero/One for a refined numeric type
  // - checked arithmetic that returns Either (no throwing)
  // - minimal assumptions: "T is a refined view of the underlying primitive"
  trait SizeTrait[Tpe, T]:
    def either(value: Tpe): Either[String, T]
    def unsafe(value: Tpe): T

    def minValue: Tpe
    def maxValue: Tpe
    def zeroValue: Tpe
    def oneValue: Tpe

    final lazy val Min: T  = unsafe(minValue)
    final lazy val Max: T  = unsafe(maxValue)
    final lazy val Zero: T = unsafe(zeroValue)
    final lazy val One: T  = unsafe(oneValue)

    // Refined numeric types in this codebase are runtime-identical to their underlying primitives.
    // Keeping this local avoids requiring any global implicit conversions.
    protected inline def raw(value: T): Tpe = value.asInstanceOf[Tpe]

    extension (value: T)(using integral: Integral[Tpe])
      def checkedAdd(other: T): Either[String, T] = either(integral.plus(raw(value), raw(other)))
      def checkedSub(other: T): Either[String, T] = either(integral.minus(raw(value), raw(other)))
      def checkedMul(other: T): Either[String, T] = either(integral.times(raw(value), raw(other)))

      inline def increment(n: Int): Either[String, T] =
        if n < 0 then Left(s"increment must be non-negative, got $n")
        else either(integral.plus(raw(value), integral.fromInt(n)))

  // --- String constraints
  type AlgoConstraint     = Match["(sha-256|sha-1|blake3|md5)"]
  type HexLowerConstraint = Match["[0-9a-f]{1,64}"]
  type HexUpperConstraint = Match["[0-9A-F]{1,64}"]
  type HexConstraint      = HexLowerConstraint | HexUpperConstraint
  type KekIdConstraint    = Match["[A-Za-z0-9:_-]{4,128}"]

  // --- Numeric types
  //
  // `types.*` is the preferred import path across the repo, but the actual numeric
  // constraints live in `graviton.core.model.ByteConstraints`.
  type BlockSize  = graviton.core.model.BlockSize
  type ChunkSize  = graviton.core.model.UploadChunkSize
  type FileSize   = graviton.core.model.FileSize
  type ChunkCount = graviton.core.model.ChunkCount
  type BlockIndex = graviton.core.model.BlockIndex

  // Generic non-negative size (offsets, totals, etc). `FileSize` already enforces >= 0.
  type Size = FileSize

  // Keep this value available for runtime checks.
  val MaxBlockBytes: Int = graviton.core.model.ByteConstraints.MaxBlockBytes

  object BlockSizeOps extends SizeTrait[Int, BlockSize]:
    import graviton.core.model.ByteConstraints
    def either(value: Int): Either[String, BlockSize] = ByteConstraints.refineBlockSize(value)
    def unsafe(value: Int): BlockSize                 = ByteConstraints.unsafeBlockSize(value)
    val minValue: Int                                 = 1
    val maxValue: Int                                 = ByteConstraints.MaxBlockBytes
    val zeroValue: Int                                = 0
    val oneValue: Int                                 = 1

  object UploadChunkSizeOps extends SizeTrait[Int, ChunkSize]:
    import graviton.core.model.ByteConstraints
    def either(value: Int): Either[String, ChunkSize] = ByteConstraints.refineUploadChunkSize(value)
    def unsafe(value: Int): ChunkSize                 = value.asInstanceOf[ChunkSize]
    val minValue: Int                                 = ByteConstraints.MinUploadChunkBytes
    val maxValue: Int                                 = ByteConstraints.MaxUploadChunkBytes
    val zeroValue: Int                                = 0
    val oneValue: Int                                 = 1

  object FileSizeOps extends SizeTrait[Long, FileSize]:
    import graviton.core.model.ByteConstraints
    def either(value: Long): Either[String, FileSize] = ByteConstraints.refineFileSize(value)
    def unsafe(value: Long): FileSize                 = ByteConstraints.unsafeFileSize(value)
    val minValue: Long                                = ByteConstraints.MinFileBytes
    val maxValue: Long                                = Long.MaxValue
    val zeroValue: Long                               = 0L
    val oneValue: Long                                = 1L

  object ChunkCountOps extends SizeTrait[Long, ChunkCount]:
    import graviton.core.model.ByteConstraints
    def either(value: Long): Either[String, ChunkCount] = ByteConstraints.refineChunkCount(value)
    def unsafe(value: Long): ChunkCount                 = value.asInstanceOf[ChunkCount]
    val minValue: Long                                  = 0L
    val maxValue: Long                                  = Long.MaxValue
    val zeroValue: Long                                 = 0L
    val oneValue: Long                                  = 1L

  object BlockIndexOps extends SizeTrait[Long, BlockIndex]:
    import graviton.core.model.ByteConstraints
    def either(value: Long): Either[String, BlockIndex] = ByteConstraints.refineBlockIndex(value)
    def unsafe(value: Long): BlockIndex                 = value.asInstanceOf[BlockIndex]
    val minValue: Long                                  = 0L
    val maxValue: Long                                  = Long.MaxValue
    val zeroValue: Long                                 = 0L
    val oneValue: Long                                  = 1L

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

  // Config-ish numeric values.
  type CompressionLevel = Int
  type NonceLength      = Int

  type KekId = KekId.T
  object KekId extends RefinedTypeExt[String, KekIdConstraint]

  type LocatorScheme = LocatorScheme.T
  object LocatorScheme extends RefinedTypeExt[String, Match["[a-z0-9+.-]+"]]

  type PathSegment = PathSegment.T
  object PathSegment extends RefinedTypeExt[String, Match["[^/]+"] & MinLength[1]]

  type FileSegment = FileSegment.T
  object FileSegment extends RefinedTypeExt[String, Match["[^/]+"] & MinLength[1]]

  // MIME type (very light validation; tighten if/when a stricter policy is required).
  type Mime = Mime.T
  object Mime extends RefinedTypeExt[String, MinLength[1] & MaxLength[256]]

  // ---------------------------
  // Digest validation
  // ---------------------------

  private val Sha256HexLength = 64
  private val Sha1HexLength   = 40
  private val Md5HexLength    = 32

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
