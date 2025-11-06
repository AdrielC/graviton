package graviton.core
package model

import graviton.GravitonError
import io.github.iltotore.iron.{zio as _, *}
import io.github.iltotore.iron.constraint.all.*
import zio.*
import zio.stream.*

import scala.compiletime.ops.string.`+`

import scala.annotation.targetName
import graviton.SubtypeExt

/** Project-wide constant limits. */
object Limits:
  final val MAX_BLOCK_SIZE_IN_BYTES = ByteConstraints.MAX_BLOCK_SIZE_IN_BYTES
  final type MAX_BLOCK_SIZE_IN_BYTES = ByteConstraints.MAX_BLOCK_SIZE_IN_BYTES.type

  final val MAX_FILE_SIZE_IN_BYTES = ByteConstraints.MAX_FILE_SIZE_IN_BYTES
  final type MAX_FILE_SIZE_IN_BYTES = MAX_FILE_SIZE_IN_BYTES.type

/** Size measured in bytes. Always strictly positive. */

object Sized:
  import scala.compiletime.ops.any.ToString
  import scala.compiletime.ops.string.`+`
  type TypeConstr[Min, Max] = GreaterEqual[Min] & LessEqual[Max]
  type ConstrDesc[Min, Max] = "Size must be between " + ToString[Min] + " and " + ToString[Max]
  type Constr[Min, Max]     = DescribedAs[TypeConstr[Min, Max], ConstrDesc[Min, Max]]

end Sized

transparent sealed trait Sized[
  N <: Int | Long: Integral,
  _Min <: N,
  _Max <: N,
](using _Min: Constraint[_Min, GreaterEqual[_Max]], _Max: Constraint[_Max, LessEqual[_Min]])
    extends SubtypeExt[N, Sized.Constr[_Min, _Max]]:
  self: SubtypeExt[N, Sized.Constr[_Min, _Max]] =>

  val _ = _Min
  val _ = _Max

  import Integral.Implicits.infixIntegralOps
  given Integral[T] = self.assumeAll[Integral](summon[Integral[N]])

  final type V[Val <: N] = Val & T
  inline def V[Val <: N]: Val & T =
    inline rtc.test(scala.compiletime.constValue[Val]) match
      case Left(err) => compiletime.error(rtc.message)
      case Right(n)  => n.asInstanceOf[Val & T]

  final type TypeConstr = Sized.TypeConstr[_Min, _Max]
  final type ConstrDesc = Sized.ConstrDesc[_Min, _Max]
  final type Constr     = Sized.Constr[_Min, _Max]
  final type Max        = T
  final type Min        = T

  inline transparent def max: Max = applyUnsafe(compiletime.constValue[_Max]).asInstanceOf[Max]
  inline transparent def min: Min = applyUnsafe(compiletime.constValue[_Min]).asInstanceOf[Min]

  inline transparent def Max: Max = max
  inline transparent def Min: Min = min

  infix type *[M <: N, NN <: Int | Long] <: V[N] = M match
    case Int  =>
      NN match
        case Int  => V[scala.compiletime.ops.int.`*`[M, NN]]
        case Long => V[scala.compiletime.ops.int.`*`[M, scala.compiletime.ops.long.ToInt[NN]]]
    case Long =>
      NN match
        case Int  => V[scala.compiletime.ops.long.`*`[M, scala.compiletime.ops.int.ToLong[NN]]]
        case Long => V[scala.compiletime.ops.long.`*`[M, NN]]

  extension (self: T)

    @targetName("add")
    def ++[TT <: T](other: TT): Option[T] = option(self + other)

    @targetName("sub")
    def --[TT <: T](other: TT): Option[T] = option(self - other)

  end extension

  final type B[NN] <: V[NN] = NN match
    case Int  => V[NN]
    case Long => V[NN]
  end B
  final type KB[NN]         = V[B[NN] * 1024]
  final type MB[NN]         = V[KB[NN] * 1024]
  final type GB[NN]         = V[MB[NN] * 1024]

  final type TB[NN] = V[GB[NN] * 1024]

  inline final def TB[NN <: Int | Long]: TB[NN] = V[TB[NN]]
  inline final def GB[NN <: Int | Long]: GB[NN] = V[GB[NN]]
  inline final def MB[NN <: Int | Long]: MB[NN] = V[MB[NN]]
  inline final def KB[NN <: Int | Long]: KB[NN] = V[KB[NN]]
  inline final def B[NN <: Int | Long]: B[NN]   = V[B[NN]]

end Sized

transparent trait ByteSize[
  N <: Int | Long,
  Mn <: N,
  Mx <: N,
] extends Sized[N, Mn, Mx]:
  self: Sized[N, Mn, Mx] =>

  final type Zero = V[ZeroOf[N]]
  final type One  = V[OneOf[N]]
  inline transparent def zero: T = applyUnsafe(compiletime.constValue[ZeroOf[N]])
  inline transparent def one: T  = applyUnsafe(compiletime.constValue[OneOf[N]])

end ByteSize

type ZeroOf[N <: Int | Long | Sized[?, ?, ?]] <: (Int | Long | Sized[?, ?, ?]) & N = N match
  case Int            => 0 & N
  case Long           => 0L & N
  case Sized[n, _, _] => ZeroOf[n] & N

type OneOf[N <: Int | Long | Sized[?, ?, ?]] <: (Int | Long | Sized[?, ?, ?]) & N = N match
  case Int            => 1 & N
  case Long           => 1L & N
  case Sized[n, _, _] => OneOf[n] & N

/** Immutable block of bytes constrained to 1..=MAX_BLOCK_SIZE_IN_BYTES. */

type MaxOf[N <: Int | Long | Sized[?, ?, ?]] <: (Int | Long | Sized[?, ?, ?]) & N = N match
  case Int              => Int.MaxValue.type & N
  case Long             => Long.MaxValue.type & N
  case Sized[n, mn, mx] => MaxOf[mx] & N

transparent sealed trait NonNegativeSize[N <: Int | Long] extends Sized[N, ZeroOf[N], MaxOf[N]]:
  self: Sized[N, ZeroOf[N], MaxOf[N]] =>

  // given Ordering[N] = Ordering.by(applyUnsafe(_).toLong)

  final type Zero = V[ZeroOf[N]]
  final type One  = V[OneOf[N]]
  inline transparent def zero: T = applyUnsafe(compiletime.constValue[ZeroOf[N]])
  inline transparent def one: T  = applyUnsafe(compiletime.constValue[OneOf[N]])

end NonNegativeSize

type Size = Size.T
object Size extends NonNegativeSize[Int]

type SizeLong = SizeLong.T
object SizeLong extends NonNegativeSize[Long]

transparent sealed trait BoundedIntSize[Min <: Int, Max <: Int] extends NonNegativeSize[Int]

transparent sealed trait BoundedLongSize[Min <: Long, Max <: Long] extends NonNegativeSize[Long]

type BlockSize = BlockSize.T
object BlockSize extends BoundedIntSize[ByteConstraints.BlockSize.Min, ByteConstraints.BlockSize.Max]

type FileSize = FileSize.T
object FileSize extends BoundedLongSize[ByteConstraints.FileSize.Min, ByteConstraints.FileSize.Max]

type UploadChunkSize = UploadChunkSize.T
object UploadChunkSize extends BoundedIntSize[ByteConstraints.UploadChunkSize.Min, ByteConstraints.UploadChunkSize.Max]

object IndexConstraint:
  object NonNegative:
    given RuntimeConstraint[Long, NonNegative.type] =
      RuntimeConstraint[Long, NonNegative.type]((value: Long) => value >= 0L, "index must be >= 0")

/** Zero-based indices. */
type Index = Index.T
object Index extends NonNegativeSize[Long]

type BlockLength = BlockSize.T
final val BlockLength = BlockSize

type Block = Block.T
object Block extends SubtypeExt[Chunk[Byte], Length[BlockLength.Constr]]:
  self =>

  def fromChunk(chunk: Chunk[Byte]): Either[String, Block] =
    self.either(chunk)

  def fromChunkZIO(chunk: Chunk[Byte]): ZIO[Any, GravitonError, Block] =
    ZIO.fromEither(self.either(chunk)).mapError(err => GravitonError.CorruptData("Invalid block chunk: " + chunk.length + " bytes: " + err))

  extension (block: Block)
    def bytes: NonEmptyChunk[Byte] = NonEmptyChunk.fromChunk(block).get
    def length: Int                = block.length
    def blockSize: BlockSize       = BlockSize.applyUnsafe(block.length.toInt)
    def fileSize: FileSize         = FileSize.applyUnsafe(block.blockSize.toLong)

end Block

object BlockBuilder:
  import Limits.MAX_BLOCK_SIZE_IN_BYTES

  def chunkify(bytes: Chunk[Byte]): Chunk[Block] =
    if bytes.isEmpty then Chunk.empty
    else
      val grouped = bytes.grouped(MAX_BLOCK_SIZE_IN_BYTES)
      Chunk.fromIterator(
        grouped.flatMap { group =>
          val chunk = Chunk.fromIterable(group)
          Block.fromChunk(chunk).toOption.iterator
        }
      )

  def rechunk(max: BlockSize = BlockSize.max): ZPipeline[Any, GravitonError, Byte, Block] =
    ZPipeline.fromChannel {
      def emitFull(bytes: Chunk[Byte]): IO[GravitonError, (Chunk[Block], Chunk[Byte])] =
        var rest = bytes
        val out  = ChunkBuilder.make[Block]()
        while rest.length >= max do
          val (full, leftover) = rest.splitAt(max)
          Block.fromChunk(full) match
            case Left(err)  => return ZIO.fail(GravitonError.PolicyViolation(err))
            case Right(blo) =>
              out += blo
              rest = leftover
        ZIO.succeed(out.result() -> rest)

      def loop(buffer: Chunk[Byte]): ZChannel[Any, GravitonError, Chunk[Byte], Any, GravitonError, Chunk[Block], Any] =
        ZChannel.readWith(
          (incoming: Chunk[Byte]) =>
            ZChannel.fromZIO(emitFull(buffer ++ incoming)).flatMap { case (emitted, rest) =>
              if emitted.isEmpty then loop(rest)
              else ZChannel.write(emitted) *> loop(rest)
            },
          (err: GravitonError) => ZChannel.fail(err),
          (_: Any) =>
            if buffer.isEmpty then ZChannel.unit
            else
              ZChannel
                .fromEither(
                  Block.either(buffer).left.map(GravitonError.PolicyViolation(_))
                )
                .flatMap(block => ZChannel.write(Chunk.single(block))),
        )
      loop(Chunk.empty)
    }
