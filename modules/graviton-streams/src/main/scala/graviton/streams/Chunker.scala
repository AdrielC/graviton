package graviton.streams

import graviton.core.GravitonError
import graviton.core.model.Block
import graviton.core.types.*
import zio.*
import zio.stream.*

import scala.Conversion

trait Chunker:
  def name: String
  def pipeline: ZPipeline[Any, Chunker.Err, Byte, Block]

object Chunker:
  type Err = ChunkerCore.Err

  final case class Failure(err: Err) extends Exception(err.message)

  /** Convert a chunker error to the unified `GravitonError` hierarchy. */
  def toGravitonError(err: Err): GravitonError =
    err.toGravitonError

  /** Convert a chunker error to a `Throwable` (for untyped error channels). */
  def toThrowable(err: Err): Throwable =
    err.toGravitonError.toThrowable

  private val defaultChunkSize: UploadChunkSize =
    // Compile-time refined (Iron) for a compile-time constant.
    UploadChunkSize(1024 * 1024)

  val default: Chunker = fixed(defaultChunkSize)

  val current: FiberRef[Chunker] =
    Unsafe.unsafe(implicit unsafe => FiberRef.unsafe.make(default))

  def locally[R, E, A](chunker: Chunker)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    current.locally(chunker)(effect)

  def fixed(size: UploadChunkSize, label: Option[String] = None): Chunker =
    val sizeBytes = size.value
    val pipeline  = Incremental.pipeline(ChunkerCore.Mode.Fixed(chunkBytes = sizeBytes))
    SimpleChunker(label.getOrElse(s"fixed-$sizeBytes"), pipeline)

  def fastCdc(
    min: Int,
    avg: Int,
    max: Int,
    label: Option[String] = None,
  ): Chunker =
    val pipeline = Incremental.pipeline(ChunkerCore.Mode.FastCdc(minBytes = min, avgBytes = avg, maxBytes = max))
    SimpleChunker(label.getOrElse(s"fastcdc-$min-$avg-$max"), pipeline)

  def delimiter(
    delim: Chunk[Byte],
    includeDelimiter: Boolean = true,
    minBytes: Int = 1,
    maxBytes: Int = Block.maxBytes,
    label: Option[String] = None,
  ): Chunker =
    val pipeline =
      Incremental.pipeline(
        ChunkerCore.Mode.Delimiter(
          delim = delim,
          includeDelimiter = includeDelimiter,
          minBytes = minBytes,
          maxBytes = maxBytes,
        )
      )
    val dLen     = delim.length
    SimpleChunker(label.getOrElse(s"delimiter-$dLen-${if includeDelimiter then "incl" else "excl"}"), pipeline)

  given chunkerToPipeline: Conversion[Chunker, ZPipeline[Any, Err, Byte, Block]] with
    def apply(chunker: Chunker): ZPipeline[Any, Err, Byte, Block] =
      chunker.pipeline

private final case class SimpleChunker(
  name: String,
  pipeline: ZPipeline[Any, Chunker.Err, Byte, Block],
) extends Chunker

private object Incremental:

  def pipeline(
    mode: ChunkerCore.Mode
  ): ZPipeline[Any, Chunker.Err, Byte, Block] =
    ZPipeline.fromChannel {
      def init: IO[Chunker.Err, ChunkerCore.State] =
        ZIO.fromEither(ChunkerCore.init(mode))

      def loop(st0: ChunkerCore.State): ZChannel[Any, Chunker.Err, Chunk[Byte], Any, Chunker.Err, Chunk[Block], Any] =
        ZChannel.readWith(
          (in: Chunk[Byte]) =>
            ZChannel
              .fromZIO {
                ZIO
                  .fromEither(st0.step(in))
              }
              .flatMap { case (st2, out) =>
                ZChannel.write(out) *> loop(st2)
              },
          err => ZChannel.fail(err),
          _ =>
            // end-of-stream: flush remaining bytes as a final block (if non-empty)
            ZChannel
              .fromZIO {
                ZIO.fromEither(st0.finish).map(_._2)
              }
              .flatMap(out => ZChannel.write(out) *> ZChannel.unit),
        )

      ZChannel.fromZIO(init).flatMap(loop)
    }
