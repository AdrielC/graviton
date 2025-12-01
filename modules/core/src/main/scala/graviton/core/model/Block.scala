package graviton.core
package model

import graviton.GravitonError
import io.github.iltotore.iron.{zio as _, *}
import io.github.iltotore.iron.constraint.all.*
import zio.*
import zio.stream.*

import scala.compiletime.ops.string.`+`
import graviton.GravitonError.ChunkerFailure

import scala.annotation.targetName
import graviton.SubtypeExt

import graviton.chunking.ChunkingPipeline

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

  opaque type Sized[A <: N] <: (A & N) :| Sized.Constr[_Min, _Max] = (A & N) :| Sized.Constr[_Min, _Max]

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

  inline transparent def max: Max & T = applyUnsafe(compiletime.constValue[_Max]).asInstanceOf[Max & T]
  inline transparent def min: Min & T = applyUnsafe(compiletime.constValue[_Min]).asInstanceOf[Min & T]

  inline transparent def Max: Max & T = max
  inline transparent def Min: Min & T = min

  infix type *[M <: N, NN <: Int | Long] <: V[N] = M match
    case Int  =>
      NN match
        case Int  => V[scala.compiletime.ops.int.`*`[M, NN]]
        case Long => V[scala.compiletime.ops.int.`*`[M, scala.compiletime.ops.long.ToInt[NN]]]
    case Long =>
      NN match
        case Int  => V[scala.compiletime.ops.long.`*`[M, scala.compiletime.ops.int.ToLong[NN]]]
        case Long => V[scala.compiletime.ops.long.`*`[M, NN]]

  extension (a: T)
    
    @targetName("add")
    def ++[TT <: T](other: TT): Option[T] = option(a + other)

    @targetName("sub")
    def --[TT <: T](other: TT): Option[T] = option(a - other)

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
  transparent inline given this.type = this
  // given Ordering[N] = Ordering.by(applyUnsafe(_).toLong)

  final type Zero = V[ZeroOf[N]]
  final type One  = V[OneOf[N]]
  inline transparent def zero: T = applyUnsafe(compiletime.constValue[ZeroOf[N]])
  inline transparent def one: T  = applyUnsafe(compiletime.constValue[OneOf[N]])

end NonNegativeSize


transparent sealed trait BoundedIntSize[Min <: Int, Max <: Int] extends NonNegativeSize[Int]
object BoundedIntSize:
  self =>
  transparent inline given self.type = self

  transparent inline def apply[Min <: Int, Max <: Int, TT <: BoundedIntSize[Min, Max]](using T: TT): TT & T.type & BoundedIntSize[Min, Max] = 
    T

transparent sealed trait BoundedLongSize[Min <: Long, Max <: Long] extends NonNegativeSize[Long]:
  self: BoundedLongSize[Min, Max] =>
  transparent inline given self.type = self


type Size = Size.T
object Size extends BoundedIntSize[1, Int.MaxValue.type]

type SizeLong = SizeLong.T
object SizeLong extends BoundedLongSize[1L, Long.MaxValue.type]


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

  extension (block: Block)
    def hex: String = ByteVector(bytes.toArray).toHex
    def bytes: Chunk[Byte] = block
    def length: BlockLength = BlockLength.applyUnsafe(block.length)
    def blockSize: BlockSize = BlockSize.applyUnsafe(block.length)
    def fileSize: FileSize = FileSize.applyUnsafe(block.blockSize.toLong)
    def toBytesStream: graviton.BytesStream = ZStream.succeed(toBytes)
    def toBytes: graviton.Bytes = graviton.Bytes(toBytesStream)

  def fromChunk(chunk: Chunk[Byte]): Either[String, Block] =
    either(chunk)

  def fromNonEmptyChunk(chunk: NonEmptyChunk[Byte]): Either[String, Block] =
    either(chunk.toChunk)

  def fromChunkZIO(chunk: Chunk[Byte]): ZIO[Any, GravitonError, Block] =
    ZIO.fromEither(fromChunk(chunk)).mapError(err => 
      GravitonError.PolicyViolation("Invalid block chunk: " + chunk.length + " bytes: " + err
    ))

end Block

import scodec.bits.ByteVector

object BlockStringInterpolator:
  
  extension (block: Block)
    def hex: String = ByteVector(block.bytes.toArray).toHex
    def toBytesStream: graviton.Bytes = graviton.Bytes(ZStream.fromChunk(block))
    def bytes: NonEmptyChunk[Byte]    = NonEmptyChunk.fromChunk(block).get
    def length: Int                   = block.length
    def blockSize: BlockSize          = BlockSize.applyUnsafe(block.length.toInt)
    def fileSize: FileSize            = FileSize.applyUnsafe(block.blockSize.toLong)


  /** Inline string interpolator for Block hex encoding and parsing.
    *
    * Usage:
    *   val block: Block = ...
    *   val hex: String = bh"${block}"
    *   val parsed: Block = bh"deadbeef"
    */
  inline def apply(ctx: StringContext)(inline args: Any*): String =
    ${ BlockStringInterpolatorMacro.impl('ctx, 'args) }

  object BlockStringInterpolatorMacro:
    import scala.quoted.*

    def impl[A: Type](ctxExpr: Expr[StringContext], argsExpr: Expr[Seq[A]])(using Quotes): Expr[String] =
      import quotes.reflect.*
      (ctxExpr, argsExpr) match
        // Compile-time hex interpolation: bh"deadbeef"
        case (Expr(sc @ StringContext(Seq(singlePart))), Seq()) =>
          val str = singlePart.toString.trim
          try
            val bv = ByteVector.fromValidHex(str)
            Expr(bv.toHex)
          catch
            case _: Throwable =>
              report.error(s"Invalid hex string for Block: '$str'")
              Expr("")
        // Interpolation with blocks: bh"$block"
        case (Expr(sc @ StringContext(parts @ _*)), args) =>
          '{ 
            val resolved = new StringContext(${Varargs(parts.map(Expr(_))) }*).s(${args}*)
            try scodec.bits.ByteVector.fromValidHex(resolved).toHex
            catch
              case _: Throwable => throw new IllegalArgumentException(s"Invalid block hex: '${resolved}'")
          }
        case _ =>
          report.error("bh interpolator: unsupported usage")
          '{""}

end BlockStringInterpolator

export BlockStringInterpolator.given

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

  def rechunk(max: BlockSize = BlockSize.max): ChunkingPipeline =
    ChunkingPipeline:
      ZPipeline.fromChannel {
        def emitFull(bytes: Chunk[Byte]): IO[ChunkerFailure, (Chunk[Block], Chunk[Byte])] =
          var rest = bytes
          val out  = ChunkBuilder.make[Block]()
          while rest.length >= max do
            val (full, leftover) = rest.splitAt(max)
            Block.fromChunk(full) match
              case Left(err)  => return ZIO.fail(ChunkerFailure(err))
              case Right(blo) =>
                out += blo
                rest = leftover
          ZIO.succeed(out.result() -> rest)

        def loop(buffer: Option[Block]): ZChannel[Any, ChunkerFailure, Chunk[Byte], Any, ChunkerFailure, Chunk[Block], Any] =
          ZChannel.readWith(
            (incoming: Chunk[Byte]) =>
              ZChannel
                .fromZIO(
                  buffer
                    .map(b => emitFull(b ++ incoming))
                    .getOrElse(emitFull(incoming))
                )
                .flatMap { case (emitted, rest) =>
                  Block.fromChunk(rest) match
                    case Left(err)    => ZChannel.fail(ChunkerFailure(err))
                    case Right(block) =>
                      if emitted.isEmpty then loop(Some(block))
                      else ZChannel.write(emitted) *> loop(Some(block))
                },
            (err: ChunkerFailure) => ZChannel.fail(err),
            (_: Any) =>
              if buffer.isEmpty then ZChannel.unit
              else ZChannel.write(buffer.map(Chunk.single).getOrElse(Chunk.empty)),
          )
        loop(None)
      }
