package graviton.streams

import graviton.core.model.Block
import graviton.core.types.*
import zio.*
import zio.stream.*

import scala.Conversion

trait Chunker:
  def name: String
  def pipeline: ZPipeline[Any, Throwable, Byte, Block]

object Chunker:
  private val DefaultChunkBytes = 1024 * 1024

  private val defaultChunkSize: UploadChunkSize =
    UploadChunkSize.either(DefaultChunkBytes).fold(_ => UploadChunkSize.unsafe(DefaultChunkBytes), identity)

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

  given chunkerToPipeline: Conversion[Chunker, ZPipeline[Any, Throwable, Byte, Block]] with
    def apply(chunker: Chunker): ZPipeline[Any, Throwable, Byte, Block] =
      chunker.pipeline

private final case class SimpleChunker(
  name: String,
  pipeline: ZPipeline[Any, Throwable, Byte, Block],
) extends Chunker

private object Incremental:

  def pipeline(
    mode: ChunkerCore.Mode,
  ): ZPipeline[Any, Throwable, Byte, Block] =
    ZPipeline.fromChannel {
      def init: IO[Throwable, ChunkerCore.State] =
        ZIO.fromEither(ChunkerCore.init(mode).left.map(toThrowable))

      def loop(st0: ChunkerCore.State): ZChannel[Any, Throwable, Chunk[Byte], Any, Throwable, Chunk[Block], Any] =
        ZChannel.readWith(
          (in: Chunk[Byte]) =>
            ZChannel.fromZIO {
              ZIO
                .fromEither(st0.step(in))
                .mapError(toThrowable)
            }.flatMap { case (st2, out) =>
              ZChannel.write(out) *> loop(st2)
            },
          err => ZChannel.fail(err),
          _ =>
            // end-of-stream: flush remaining bytes as a final block (if non-empty)
            ZChannel.fromZIO {
              ZIO.fromEither(st0.finish).mapError(toThrowable).map(_._2)
            }.flatMap(out => ZChannel.write(out) *> ZChannel.unit),
        )

      ZChannel.fromZIO(init).flatMap(loop)
    }

  private def toThrowable(err: ChunkerCore.Err): Throwable =
    err match
      case ChunkerCore.Err.EmptyDelimiter        => new IllegalArgumentException("Delimiter cannot be empty")
      case ChunkerCore.Err.InvalidBounds(msg)    => new IllegalArgumentException(msg)
      case ChunkerCore.Err.InvalidDelimiter(msg) => new IllegalArgumentException(msg)
      case ChunkerCore.Err.InvalidBlock(msg)     => new IllegalArgumentException(msg)
