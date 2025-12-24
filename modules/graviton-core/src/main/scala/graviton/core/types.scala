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
