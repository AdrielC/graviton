package graviton.streams

import graviton.core.model.{Block, ByteConstraints}
import graviton.core.scan.FS
import graviton.core.scan.FS.*
import graviton.core.types.*
import zio.{Chunk, FiberRef, Unsafe, ZIO}
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

  given chunkerToPipeline: Conversion[Chunker, ZPipeline[Any, Throwable, Byte, Block]] with
    def apply(chunker: Chunker): ZPipeline[Any, Throwable, Byte, Block] =
      chunker.pipeline

private final case class SimpleChunker(
  name: String,
  pipeline: ZPipeline[Any, Throwable, Byte, Block],
) extends Chunker
