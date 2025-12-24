package graviton.streams

import graviton.core.model.{Block, ByteConstraints}
import graviton.core.scan.FS
import graviton.core.scan.FS.*
import graviton.core.scan.IngestScan
import graviton.core.types.*
import zio.{Chunk, FiberRef, Unsafe, ZIO, ChunkBuilder}
import zio.stream.ZPipeline

import scala.Conversion

trait Chunker:
  def name: String
  def pipeline: ZPipeline[Any, Throwable, Byte, Block]

object Chunker:
  private val DefaultChunkBytes = 1024 * 1024

  private val defaultChunkSize: ChunkSize =
    ByteConstraints
      .refineUploadChunkSize(DefaultChunkBytes)
      .fold(err => throw new IllegalStateException(s"Invalid default chunk size ($DefaultChunkBytes): $err"), identity)

  val default: Chunker = fixed(defaultChunkSize)

  val current: FiberRef[Chunker] =
    Unsafe.unsafe(implicit unsafe => FiberRef.unsafe.make(default))

  def locally[R, E, A](chunker: Chunker)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    current.locally(chunker)(effect)

  def fixed(size: ChunkSize, label: Option[String] = None): Chunker =
    val sizeBytes = size
    val scan      = FS.fixedChunker(sizeBytes).optimize.toPipeline
    val pipeline  =
      (ZPipeline.identity[Byte].chunks >>> scan)
        .mapChunksZIO { chunkOfBlocks =>
          ZIO
            .foreach(chunkOfBlocks) { bytes =>
              ZIO.fromEither(Block.fromChunk(bytes)).mapError(msg => new IllegalArgumentException(msg))
            }
            .map(blocks => Chunk.fromIterable(blocks))
        }
    SimpleChunker(label.getOrElse(s"fixed-$sizeBytes"), pipeline)

  def fastCdc(
    min: Int,
    avg: Int,
    max: Int,
    label: Option[String] = None,
  ): Chunker =
    val scan = IngestScan.fastCdc(minSize = min, avgSize = avg, maxSize = max).optimize.toPipeline

    val pipeline =
      (ZPipeline.identity[Byte].chunks >>> scan)
        .mapChunksZIO { events =>
          def field[A](e: IngestScan.Event, name: String): A =
            e.toMap
              .collectFirst { case (f, v) if f.name == name => v.asInstanceOf[A] }
              .getOrElse(throw new NoSuchElementException(s"Missing field '$name' in IngestScan.Event"))

          val builder = ChunkBuilder.make[Block]()
          var idx     = 0
          var failure = Option.empty[Throwable]
          while idx < events.length do
            val e    = events(idx)
            val kind = field[String](e, "kind")
            if kind == "block" && failure.isEmpty then
              field[Option[Chunk[Byte]]](e, "blockBytes") match
                case Some(bytes) =>
                  Block.fromChunk(bytes) match
                    case Right(block) => builder += block
                    case Left(err)    => failure = Some(new IllegalArgumentException(err))
                case None        => failure = Some(new IllegalStateException("FastCDC emitted a block event without bytes"))
            idx += 1
          failure match
            case Some(err) => ZIO.fail(err)
            case None      => ZIO.succeed(builder.result())
        }

    SimpleChunker(label.getOrElse(s"fastcdc-$min-$avg-$max"), pipeline)

  given chunkerToPipeline: Conversion[Chunker, ZPipeline[Any, Throwable, Byte, Block]] with
    def apply(chunker: Chunker): ZPipeline[Any, Throwable, Byte, Block] =
      chunker.pipeline

private final case class SimpleChunker(
  name: String,
  pipeline: ZPipeline[Any, Throwable, Byte, Block],
) extends Chunker
