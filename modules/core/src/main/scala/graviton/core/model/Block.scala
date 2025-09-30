package graviton.core.model

import graviton.GravitonError
import io.github.iltotore.iron.{zio => _, *}
import io.github.iltotore.iron.constraint.numeric.*
import zio.*
import zio.stream.*

/** Project-wide constant limits. */
object Limits:
  inline val MAX_BLOCK_SIZE_IN_BYTES = 1048576

/** Size measured in bytes. Always strictly positive. */
type Size = Int :| Positive

object Size:
  def apply(n: Int): Either[String, Size] = n.refineEither[Positive]

  def fromZIO(n: Int): IO[GravitonError, Size] =
    ZIO.fromEither(apply(n)).mapError(msg => GravitonError.PolicyViolation(msg))

object IndexConstraint:
  object NonNegative:
    given RuntimeConstraint[Long, NonNegative.type] =
      RuntimeConstraint[Long, NonNegative.type]((value: Long) => value >= 0L, "index must be >= 0")

/** Zero-based indices. */
type Index = Long :| IndexConstraint.NonNegative.type

object Index:
  def apply(n: Long): Either[String, Index] = n.refineEither[IndexConstraint.NonNegative.type]

/** Immutable block of bytes constrained to 1..=MAX_BLOCK_SIZE_IN_BYTES. */
final case class Block private (bytes: Chunk[Byte], size: Size):
  def toChunk: Chunk[Byte] = bytes
  def toArray: Array[Byte] = bytes.toArray

object Block:
  import Limits.MAX_BLOCK_SIZE_IN_BYTES

  def fromChunk(bytes: Chunk[Byte]): Either[String, Block] =
    val length = bytes.length
    if length <= 0 then Left("chunk length must be > 0")
    else if length > MAX_BLOCK_SIZE_IN_BYTES then Left(s"chunk length exceeds $MAX_BLOCK_SIZE_IN_BYTES")
    else Size(length).map(size => Block(bytes, size))

  def fromChunkZIO(bytes: Chunk[Byte]): IO[GravitonError, Block] =
    ZIO.fromEither(fromChunk(bytes)).mapError(GravitonError.PolicyViolation.apply)

  def unsafeFromChunk(bytes: Chunk[Byte]): Block =
    fromChunk(bytes).fold(err => throw new IllegalArgumentException(err), identity)

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

  def rechunk(max: Int = MAX_BLOCK_SIZE_IN_BYTES): ZPipeline[Any, GravitonError, Byte, Block] =
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
                .fromZIO(
                  ZIO.fromEither(Block.fromChunk(buffer)).mapError(GravitonError.PolicyViolation.apply)
                )
                .flatMap { block =>
                  ZChannel.write(Chunk.single(block))
                },
        )
      loop(Chunk.empty)
    }
