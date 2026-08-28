package graviton.core

import graviton.core.ranges.DiscreteDomain
import io.github.iltotore.iron.*
import io.github.iltotore.iron.IronType
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.numeric
import zio.schema.Schema

import scala.collection.immutable.ListMap
import scala.compiletime

// ---------------------------
// ZIO Schema + Iron glue
// ---------------------------

case class RefinedTypeExtMessage(message: String)

trait RefinedSubtypeExt[A, C] extends RefinedSubtype[A, C]:
  given schema(using underlying: Schema[A]): Schema[T] =
    underlying
      .transformOrFail(either(_), r => Right(r.value))
      .annotate(RefinedTypeExtMessage(rtc.message))

trait RefinedTypeExt[A, C] extends RefinedType[A, C]:
  given schema(using underlying: Schema[A]): Schema[T] =
    underlying
      .transformOrFail(either(_), r => Right(r.value))
      .annotate(RefinedTypeExtMessage(rtc.message))

transparent inline given ironSchema[A, B](using rtc: Constraint[A, B], schema: Schema[A]): Schema[IronType[A, B]] =
  schema
    .transformOrFail(
      value => value.refineEither[B].left.map(_ => rtc.message),
      refined => Right(refined.asInstanceOf[A]),
    )
    .annotate(RefinedTypeExtMessage(rtc.message))

given listMapSchema[K: Schema, V: Schema]: Schema[ListMap[K, V]] =
  Schema
    .map[K, V]
    .transform(m => ListMap.from(m), lm => lm.toMap)

// ---------------------------
// Types
// ---------------------------

object types:

  // --- String constraints
  type AlgoConstraint       = Match["(sha-256|sha-1|blake3|md5)"]
  type HexLowerConstraint   = Match["[0-9a-f]{1,64}"]
  type HexUpperConstraint   = Match["[0-9A-F]{1,64}"]
  type HexConstraint        = HexLowerConstraint | HexUpperConstraint
  type KekIdConstraint      = Match["[A-Za-z0-9:_-]{4,128}"]
  type IdentifierConstraint =
    Match["[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"]

  // --- total bounds (compile-time)
  final type TotalMaxT[T] <: T = T match
    case Int  => Int.MaxValue.type & T
    case Long => Long.MaxValue.type & T

  final type TotalMinT[T] <: T = T match
    case Int  => Int.MinValue.type & T
    case Long => Long.MinValue.type & T

  // ---------------------------
  // Bounded integer refinements
  // ---------------------------

  sealed trait SizeNumeric[Tpe <: Int | Long]:
    def integral: Integral[Tpe]
    def addExact(left: Tpe, right: Tpe): Option[Tpe]
    def subtractExact(left: Tpe, right: Tpe): Option[Tpe]
    def multiplyExact(left: Tpe, right: Tpe): Option[Tpe]
    def negateExact(value: Tpe): Option[Tpe]
    def quotient(left: Tpe, right: Tpe): Option[Tpe]
    def remainder(left: Tpe, right: Tpe): Option[Tpe]

  object SizeNumeric:
    given SizeNumeric[Int] with
      val integral: Integral[Int]                           = summon[Integral[Int]]
      def addExact(left: Int, right: Int): Option[Int]      =
        try Some(Math.addExact(left, right))
        catch case _: ArithmeticException => None
      def subtractExact(left: Int, right: Int): Option[Int] =
        try Some(Math.subtractExact(left, right))
        catch case _: ArithmeticException => None
      def multiplyExact(left: Int, right: Int): Option[Int] =
        try Some(Math.multiplyExact(left, right))
        catch case _: ArithmeticException => None
      def negateExact(value: Int): Option[Int]              =
        try Some(Math.negateExact(value))
        catch case _: ArithmeticException => None
      def quotient(left: Int, right: Int): Option[Int]      =
        Option.when(right != 0 && !(left == Int.MinValue && right == -1))(left / right)
      def remainder(left: Int, right: Int): Option[Int]     =
        Option.when(right != 0)(left % right)

    given SizeNumeric[Long] with
      val integral: Integral[Long]                             = summon[Integral[Long]]
      def addExact(left: Long, right: Long): Option[Long]      =
        try Some(Math.addExact(left, right))
        catch case _: ArithmeticException => None
      def subtractExact(left: Long, right: Long): Option[Long] =
        try Some(Math.subtractExact(left, right))
        catch case _: ArithmeticException => None
      def multiplyExact(left: Long, right: Long): Option[Long] =
        try Some(Math.multiplyExact(left, right))
        catch case _: ArithmeticException => None
      def negateExact(value: Long): Option[Long]               =
        try Some(Math.negateExact(value))
        catch case _: ArithmeticException => None
      def quotient(left: Long, right: Long): Option[Long]      =
        Option.when(right != 0L && !(left == Long.MinValue && right == -1L))(left / right)
      def remainder(left: Long, right: Long): Option[Long]     =
        Option.when(right != 0L)(left % right)

  trait SizeTrait[Tpe <: Int | Long]:
    type TotalMax = TotalMaxT[Tpe]
    inline def TotalMax: TotalMax = compiletime.constValue[TotalMaxT[Tpe]]

    type TotalMin = TotalMinT[Tpe]
    inline def TotalMin: TotalMin = compiletime.constValue[TotalMinT[Tpe]]

    protected given integral: Integral[Tpe]
    protected given discrete: DiscreteDomain[Tpe]

    /** Source-compatible alias for the statically owned refinement implementation. */
    type Trait[Mn <: Tpe, Mx <: Tpe, Z <: Tpe, O <: Tpe] = SizeTrait.Trait[Tpe, Mn, Mx, Z, O]

  end SizeTrait

  object SizeTrait:

    trait Trait[Tpe <: Int | Long, Mn <: Tpe, Mx <: Tpe, Z <: Tpe, O <: Tpe](
      using mnV: ValueOf[Mn],
      mxV: ValueOf[Mx],
      zV: ValueOf[Z],
      oV: ValueOf[O],
      private val sizeNumeric: SizeNumeric[Tpe],
    ) extends RefinedTypeExt[Tpe, numeric.GreaterEqual[Mn] & numeric.LessEqual[Mx]]:
      self =>

      protected final given integral: Integral[Tpe] = sizeNumeric.integral

      private def refineExact(operation: String, result: Option[Tpe]): Either[String, self.T] =
        result.toRight(s"$operation overflow").flatMap(self.either)

      type TotalMax = TotalMaxT[Tpe]
      inline def TotalMax: TotalMax = compiletime.constValue[TotalMaxT[Tpe]]

      type TotalMin = TotalMinT[Tpe]
      inline def TotalMin: TotalMin = compiletime.constValue[TotalMinT[Tpe]]

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
          sizeNumeric.addExact(v.value, integral.one).flatMap(option).getOrElse(v)

        def previous(v: self.T): self.T =
          sizeNumeric.subtractExact(v.value, integral.one).flatMap(option).getOrElse(v)

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
          sizeNumeric.addExact(x.value, y.value).flatMap(option).getOrElse(x)

        def minus(x: self.T, y: self.T): self.T =
          sizeNumeric.subtractExact(x.value, y.value).flatMap(option).getOrElse(x)

        def times(x: self.T, y: self.T): self.T =
          sizeNumeric.multiplyExact(x.value, y.value).flatMap(option).getOrElse(x)

        def negate(x: self.T): self.T =
          sizeNumeric.negateExact(x.value).flatMap(option).getOrElse(Zero)

        def quot(x: self.T, y: self.T): self.T =
          sizeNumeric.quotient(x.value, y.value).flatMap(option).getOrElse(Zero)

        def rem(x: self.T, y: self.T): self.T =
          sizeNumeric.remainder(x.value, y.value).flatMap(option).getOrElse(Zero)

      extension (value: self.T)

        // increment means add, not multiply
        inline def increment(n: Int :| numeric.GreaterEqual[0]): Either[String, self.T] =
          refineExact("addition", sizeNumeric.addExact(value.value, integral.fromInt(n)))

        infix def >==(other: self.T): Boolean = integral.gteq(value.value, other.value)
        infix def <==(other: self.T): Boolean = integral.lteq(value.value, other.value)
        infix def gt(other: self.T): Boolean  = integral.gt(value.value, other.value)
        infix def lt(other: self.T): Boolean  = integral.lt(value.value, other.value)

        def next: Option[self.T] =
          sizeNumeric.addExact(value.value, integral.one).flatMap(option)

        def previous: Option[self.T] =
          sizeNumeric.subtractExact(value.value, integral.one).flatMap(option)

        // explicit checked ops (no saturation)
        def checkedAdd(other: self.T): Either[String, self.T] =
          refineExact("addition", sizeNumeric.addExact(value.value, other.value))

        def checkedSub(other: self.T): Either[String, self.T] =
          refineExact("subtraction", sizeNumeric.subtractExact(value.value, other.value))

        def checkedMul(other: self.T): Either[String, self.T] =
          refineExact("multiplication", sizeNumeric.multiplyExact(value.value, other.value))

  end SizeTrait

  trait IntSizeTrait[N <: Int: {Integral, DiscreteDomain}] extends SizeTrait[N]:
    protected given integral: Integral[N]       = summon[Integral[N]]
    protected given discrete: DiscreteDomain[N] = summon[DiscreteDomain[N]]

  trait LongSizeTrait[N <: Long: {Integral, DiscreteDomain}] extends SizeTrait[N]:
    protected given integral: Integral[N]       = summon[Integral[N]]
    protected given discrete: DiscreteDomain[N] = summon[DiscreteDomain[N]]

  object SizeTraitInt  extends IntSizeTrait[Int]
  object SizeTraitLong extends LongSizeTrait[Long]

  // ---------------------------
  // Base families
  //
  // Law:
  // - Indexes are 0-based (min = 0)
  // - Sizes/counts/bytes are 1-based (min = 1)
  // ---------------------------

  sealed trait Size1:
    self: SizeTrait.Trait[Int, 1, Int.MaxValue.type, 0, 1] =>
  object Size extends SizeTrait.Trait[Int, 1, Int.MaxValue.type, 0, 1] with Size1
  type Size = Size.T

  sealed trait SizeLong1:
    self: SizeTrait.Trait[Long, 1L, Long.MaxValue.type, 0L, 1L] =>
  object SizeLong extends SizeTrait.Trait[Long, 1L, Long.MaxValue.type, 0L, 1L] with SizeLong1
  type SizeLong = SizeLong.T

  sealed trait IndexLong0:
    self: SizeTrait.Trait[Long, 0L, Long.MaxValue.type, 0L, 1L] =>

  object SizeSubtype     extends IntSizeTrait[Int]
  object SizeLongSubtype extends LongSizeTrait[Long]

  // Keep this value available for runtime checks.
  val MaxBlockBytes: Int = 16 * 1024 * 1024 // 16 MiB

  // Upload chunk size is the upstream chunk boundary used by streaming ingest. It must be positive
  // and must not exceed the maximum block size.
  type UploadChunkSize = UploadChunkSize.T
  object UploadChunkSize extends SizeTrait.Trait[Int, 1, 16777216, 0, 1] // 16 MiB

  type BlockSize = BlockSize.T
  object BlockSize extends SizeTrait.Trait[Int, 1, 16777216, 0, 1] // 16 MiB

  /** Maximum number of bounded block writes an ingest may execute concurrently. */
  type BlockWriteParallelism = BlockWriteParallelism.T
  object BlockWriteParallelism extends SizeTrait.Trait[Int, 1, 64, 0, 1]

  type FileSize = FileSize.T
  object FileSize extends SizeTrait.Trait[Long, 1L, 1099511627776L, 0L, 1L] // 1 TiB

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
  object BlockIndex extends SizeTrait.Trait[Long, 0L, Long.MaxValue.type, 0L, 1L] with IndexLong0

  // Offsets are 0-based byte positions within a logical blob/manifest.
  type Offset = Offset.T
  object Offset extends SizeTrait.Trait[Long, 0L, Long.MaxValue.type, 0L, 1L] with IndexLong0

  /**
   * Blob-wide byte offset (0-based).
   *
   * Prefer this name when the offset is explicitly “within a whole blob”.
   */
  opaque type BlobOffset <: Offset = Offset

  object BlobOffset:
    inline def unsafe(value: Long): BlobOffset =
      Offset.unsafe(value).asInstanceOf[BlobOffset]

    inline def either(value: Long): Either[String, BlobOffset] =
      Offset.either(value).map(_.asInstanceOf[BlobOffset])

    given Ordering[BlobOffset] =
      summon[Ordering[Offset]].asInstanceOf[Ordering[BlobOffset]]

    given Integral[BlobOffset] =
      summon[Integral[Offset]].asInstanceOf[Integral[BlobOffset]]

    given DiscreteDomain[BlobOffset] =
      summon[DiscreteDomain[Offset]].asInstanceOf[DiscreteDomain[BlobOffset]]

    given Schema[BlobOffset] =
      summon[Schema[Offset]].asInstanceOf[Schema[BlobOffset]]

  type CompressionLevel = CompressionLevel.T
  object CompressionLevel extends SizeTrait.Trait[Int, -1, 22, 0, 1]

  type KekId = KekId.T
  object KekId extends HexTrait[KekIdConstraint]

  type NonceLength = NonceLength.T
  object NonceLength extends SizeTrait.Trait[Int, 1, 32, 0, 1]

  type LocatorScheme = LocatorScheme.T
  object LocatorScheme extends RefinedTypeExt[String, Match["[a-z0-9+.-]+"] & MinLength[1] & MaxLength[64]]

  /**
   * Locator bucket/container name.
   *
   * Intentionally light:
   * - must be non-empty
   * - must not contain `/` or whitespace
   *
   * Different backends can impose stricter naming rules; those should be validated at the backend edge.
   */
  type LocatorBucket = LocatorBucket.T
  object LocatorBucket extends RefinedTypeExt[String, Match["[^/\\s]+"] & MinLength[1] & MaxLength[256]]

  /**
   * Locator path component (path under the bucket).
   *
   * Intentionally light:
   * - must be non-empty
   * - must not contain whitespace (so `scheme://bucket/path` remains unambiguous in logs and URIs)
   */
  type LocatorPath = LocatorPath.T
  object LocatorPath extends RefinedTypeExt[String, Match["[^\\s]+"] & MinLength[1] & MaxLength[2048]]

  /** A user-facing identifier (names, keys, labels) with a conservative ASCII-safe charset. */
  type Identifier = Identifier.T
  object Identifier extends RefinedTypeExt[String, IdentifierConstraint]

  /**
   * Stable repository coordination namespace.
   *
   * Every writer, reader, and maintenance process that can touch the same
   * manifest and block stores must use the same value. Keeping this distinct
   * from a raw `String` prevents accidental lock-domain drift at configuration
   * and backend boundaries.
   */
  type RepositoryNamespace = RepositoryNamespace.T
  object RepositoryNamespace extends RefinedTypeExt[String, IdentifierConstraint]

  /**
   * Custom binary attribute name.
   *
   * This is the validated form of the old `BinaryAttributes.customKeyPattern`.
   */
  type CustomAttributeName = CustomAttributeName.T
  object CustomAttributeName extends RefinedTypeExt[String, IdentifierConstraint & MaxLength[64]]

  /** Custom attribute value: bounded to keep manifests/metadata sane. */
  type CustomAttributeValue = CustomAttributeValue.T
  object CustomAttributeValue extends RefinedTypeExt[String, MaxLength[1024]]

  /** Manifest annotation key: non-semantic metadata key, bounded and ASCII-safe. */
  type ManifestAnnotationKey = ManifestAnnotationKey.T
  object ManifestAnnotationKey extends RefinedTypeExt[String, IdentifierConstraint & MaxLength[64]]

  /** Manifest annotation value: bounded to keep manifest frames and indexes small. */
  type ManifestAnnotationValue = ManifestAnnotationValue.T
  object ManifestAnnotationValue extends RefinedTypeExt[String, MaxLength[1024]]

  /** View transform name: used in hashed identity, so it must be stable and deterministic. */
  type ViewName = ViewName.T
  object ViewName extends RefinedTypeExt[String, IdentifierConstraint & MaxLength[128]]

  /** View transform argument key: part of hashed identity, so keep it stable and deterministic. */
  type ViewArgKey = ViewArgKey.T
  object ViewArgKey extends RefinedTypeExt[String, IdentifierConstraint & MaxLength[128]]

  /** View transform argument value: part of hashed identity; bounded to avoid runaway keys. */
  type ViewArgValue = ViewArgValue.T
  object ViewArgValue extends RefinedTypeExt[String, MaxLength[1024]]

  /** Optional view scope: a namespacing label for view identity derivation. */
  type ViewScope = ViewScope.T
  object ViewScope extends RefinedTypeExt[String, IdentifierConstraint & MaxLength[128]]

  type PathSegment = PathSegment.T
  object PathSegment extends RefinedTypeExt[String, Match["[^/]+"] & MinLength[1]]

  type FileSegment = FileSegment.T
  object FileSegment extends RefinedTypeExt[String, Match["[^/]+"] & MinLength[1]]

  type ChunkCount = ChunkCount.T
  object ChunkCount extends SizeTrait.Trait[Long, 1L, Long.MaxValue.type, 0L, 1L] with SizeLong1

  // Stored wire representation. External boundaries parse this as a ZIO Blocks MediaType.
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
