package graviton

import io.github.iltotore.iron.{zio as _, *}
import io.github.iltotore.iron.constraint.all.*
import zio.schema.{Schema}
import zio.*

transparent trait SubtypeExt[A, C] extends RefinedSubtype[A, C]:
  given (schema: Schema[A]) => Schema[T] = 
    schema
    .annotate(rtc)
    .transformOrFail(either(_), a => Right(a.value))
end SubtypeExt

object domain extends App:

  transparent sealed trait BytesUnit[B <: (BytesUnit[?] | Long)]:
    import scala.compiletime.ops.long.*

    final type ToUnit[B <: BytesUnit[?], N <: Long, O <: Long, L <: Long] <: Long = B match 
      case Nothing => 1L * N * O
      case Long => 1L * N * O
      case BytesUnit[O] { type Unit = L } => N * O * L

    protected type Unit <: Long

    final type UnitsAs[B <: BytesUnit[?] | Long, O <: Long] <: Long = 
      B match 
        case Nothing => 1L
        case Long => 1L * (Unit * B)
        case BytesUnit[b] { type Unit = Long } => O * Unit * ToUnit[B, 1L, O, O] * BytesUnit.ToUnit[b]

        
    final type Units[N <: Long | BytesUnit[? <: Long]] <: Long = B match 
      case Nothing => Unit * N
      case Long => Unit * B * N
      case ([l <: Long] =>> BytesUnit[?] { type Unit = l })[l] => 
        Unit * l * 1L * N * BytesUnit.ToUnit[B]
      case ([l <: Long] =>> BytesUnit[?] { type Unit = l })[l] => 
        Unit * 1L * N * l

  end BytesUnit

  object BytesUnit extends App:

    import scala.compiletime.ops.long.*

    final type ToUnit[B <: BytesUnit[? <: Long]] <: Long = B match
      case Nothing => 1L
      case ([o <: BytesUnit[? <: Long] | Long, l <: Long] =>> BytesUnit[o] { type Unit = l })[B, l] => 1L * l
      case Long => 1L * B
      case _ => 1L



    // import scala.compiletime.ops.long.*

    transparent sealed trait B extends BytesUnit[1L] { 
      override final type Unit = 1L
    }
    object B extends B

    transparent sealed trait KB extends BytesUnit[B] {
      override final type Unit = 1024L
    }
    object KB extends KB

    transparent sealed trait MB extends BytesUnit[KB] {
      override final type Unit = 1024L
    }
    object MB extends MB

    transparent sealed trait GB extends BytesUnit[MB] {
      override final type Unit = 1024L
    }
    object GB extends GB

    final type THREE = GB.Units[2L]
    final val t = (1024 * 1024 * 1024) * 2L
    final val three = scala.compiletime.constValue[THREE]

    println(three)

  type MaxBytesLength = Long.MaxValue.type
  type MaxChunkSize = Int.MaxValue.type

  type MinPartSize = MinPartSize.T
  object MinPartSize extends SubtypeExt[Int, GreaterEqual[1048576]]

  type ByteLength = ByteLength.T
  object ByteLength extends SubtypeExt[Long, GreaterEqual[1] & LessEqual[MaxBytesLength]]

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

  type NonNegLong = NonNegLong.T
  object NonNegLong extends SubtypeExt[Long, Not[Negative]]
  

