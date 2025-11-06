package graviton

import graviton.core.model.ByteConstraints
import io.github.iltotore.iron.{zio as _, *}
import io.github.iltotore.iron.constraint.all.*
import zio.schema.Schema
import zio.*

transparent trait SubtypeExt[A, C] extends RefinedSubtype[A, C]:
  given (schema: Schema[A]) => Schema[T] =
    schema
      .annotate(rtc)
      .transformOrFail(either(_), a => Right(a.value))
end SubtypeExt

transparent trait RefinedTypeExt[A: Schema, C] extends RefinedType[A, C]:

  given Schema[T] =
    summon[Schema[A]]
      .annotate(rtc)
      .transformOrFail(either(_), a => Right(a.value))

end RefinedTypeExt

object domain:

  transparent sealed trait BytesUnit[B <: (BytesUnit[?] | Long)]:
    import scala.compiletime.ops.long.*

    final type ToUnit[B <: BytesUnit[?], N <: Long, O <: Long, L <: Long] <: Long = B match
      case Nothing                        => 1L * N * O
      case Long                           => 1L * N * O
      case BytesUnit[O] { type Unit = L } => N * O * L

    protected type Unit <: Long

    final type UnitsAs[B <: BytesUnit[?] | Long, O <: Long] <: Long =
      B match
        case Nothing                           => 1L
        case Long                              => 1L * (Unit * B)
        case BytesUnit[b] { type Unit = Long } => O * Unit * ToUnit[B, 1L, O, O] * BytesUnit.ToUnit[b]

    final type Units[N <: Long | BytesUnit[? <: Long]] <: Long = B match
      case Nothing                                             => Unit * N
      case Long                                                => Unit * B * N
      case ([l <: Long] =>> BytesUnit[?] { type Unit = l })[l] =>
        Unit * l * 1L * N * BytesUnit.ToUnit[B]
      case ([l <: Long] =>> BytesUnit[?] { type Unit = l })[l] =>
        Unit * 1L * N * l

  end BytesUnit

  object BytesUnit:

    import scala.compiletime.ops.long.*

    final type ToUnit[B <: BytesUnit[? <: Long]] <: Long = B match
      case Nothing                                                                                  => 1L
      case ([o <: BytesUnit[? <: Long] | Long, l <: Long] =>> BytesUnit[o] { type Unit = l })[B, l] => 1L * l
      case Long                                                                                     => 1L * B
      case _                                                                                        => 1L

    // import scala.compiletime.ops.long.*

    transparent sealed trait B extends BytesUnit[1L] {
      override final type Unit = 1L
    }
    object B                   extends B

    transparent sealed trait KB extends BytesUnit[B] {
      override final type Unit = 1024L
    }
    object KB                   extends KB

    transparent sealed trait MB extends BytesUnit[KB] {
      override final type Unit = 1024L
    }
    object MB                   extends MB

    transparent sealed trait GB extends BytesUnit[MB] {
      override final type Unit = 1024L
    }
    object GB                   extends GB

    final type THREE = GB.Units[2L]
    final val t     = (1024 * 1024 * 1024) * 2L
    final val three = scala.compiletime.constValue[THREE]

    println(three)

  type MaxBytesLength = ByteConstraints.MAX_FILE_SIZE_IN_BYTES.type
  type MaxChunkSize   = ByteConstraints.MAX_UPLOAD_CHUNK_SIZE_IN_BYTES.type

  type MinPartSize = MinPartSize.T
  object MinPartSize extends SubtypeExt[Int, GreaterEqual[0] & LessEqual[1048576]]

  type ByteLength = ByteLength.T
  object ByteLength extends SubtypeExt[Long, GreaterEqual[0L] & LessEqual[MaxBytesLength]]

  type ToMB[N <: Int] = MB[N]

  import scala.compiletime.ops.int.*

  type MB[N <: Int] = N match
    case 1 => 1048576
    case _ => N * 1048576

  final val ONE_BYTE = 1

  type MAX_BLOCK_SIZE_IN_BYTES = 1 * 1024 * 1024 // 1MB

  final val MAX_BLOCK_SIZE_IN_BYTES = 1048576

  type Bytes = Bytes.T
  object Bytes extends SubtypeExt[zio.Chunk[Byte], MinLength[1] & MaxLength[1048576]]

  type HashBytes = HashBytes.T
  object HashBytes extends SubtypeExt[zio.Chunk[Byte], MinLength[16] & MaxLength[64]]

  type SmallBytes = SmallBytes.T
  object SmallBytes extends SubtypeExt[zio.Chunk[Byte], MaxLength[1048576]]

  type StoreKey = StoreKey.T
  object StoreKey extends SubtypeExt[zio.Chunk[Byte], FixedLength[32]]

  type PosLong = PosLong.T
  object PosLong extends SubtypeExt[Long, Positive]

  type PosInt = PosInt.T
  object PosInt extends SubtypeExt[Int, Positive]

  type NonNegLong = NonNegLong.T
  object NonNegLong extends SubtypeExt[Long, Not[Negative]]

  type NonNegInt = NonNegInt.T
  object NonNegInt extends SubtypeExt[Int, Not[Negative]]:

    type Inc[N <: NonNegInt] <: NonNegInt =
      N match
        case 0   => One
        case Max => N
        case _   =>
          N + 1 < 0 match
            case true  => Max
            case false => (N + 1) & T

    type Dec[N <: NonNegInt] <: NonNegInt =
      N match
        case 0   => N
        case Min => N
        case _   =>
          N - 1 < 0 match
            case true  => Min
            case false => (N - 1) & T

    transparent inline final def zero: T = applyUnsafe(compiletime.constValue[0])
    transparent inline final def one: T  = applyUnsafe(compiletime.constValue[1])
    transparent inline final def max: T  = applyUnsafe(compiletime.constValue[Int.MaxValue.type])
    transparent inline final def min: T  = applyUnsafe(compiletime.constValue[0])
    final type Zero = 0 & T
    final type One  = 1 & T
    final type Max  = Int.MaxValue.type & T
    final type Min  = 0 & T

    extension (self: NonNegInt)
      def add(other: NonNegInt): NonNegInt =
        ((self.value + other.value) < 0) match
          case true  => applyUnsafe(Int.MaxValue)
          case false => applyUnsafe(self + other)

      def sub(other: NonNegInt): NonNegInt =
        ((self.value - other.value) < 0) match
          case true  => zero
          case false => applyUnsafe(self - other)

      def inc: NonNegInt =
        applyUnsafe(self.add(this.one))

      def dec: NonNegInt =
        applyUnsafe(self.sub(this.one))

    end extension

    type F[N <: Nat[N], I <: NonNegInt] <: NonNegInt = N match
      case Nat.Zero.type => I
      case Nat.Succ[n]   => F[n, Inc[I]]

    def fromNat[N <: Nat[? <: N]](n: N): NonNegInt =
      n match
        case _: Nat.Zero    => NonNegInt.zero
        case _: Nat.One     => NonNegInt.one
        case n: Nat.Succ[n] => fromNat(n.prev).inc

    type FromNat[N <: Nat[?]] <: NonNegInt = N match
      case Nat.Zero    => NonNegInt.Zero
      case Nat.One     => NonNegInt.One
      case Nat.Succ[n] => Inc[FromNat[n]]

  end NonNegInt

  enum Nat[+N <: Nat[N]]:
    case Zero()                     extends Nat[Nothing]
    case One()                      extends Nat[Nat.Zero]
    case Succ[N <: Nat[N]](prev: N) extends Nat[N]

  end Nat

  object Nat:

    type Prev[N <: Nat[N]] <: Nat[?] = N match
      case Nat.Zero    => N
      case Nat.One     => Nat.Zero
      case Nat.Succ[n] => n

    type Next[N <: NonNegInt, From <: Nat[?]] <: Nat[?] =
      N match
        case NonNegInt.Zero => From
        case NonNegInt.One  => Nat.Succ[From]
        case _              => Next[NonNegInt.Dec[N], From]

  end Nat

end domain

export domain.NonNegInt
