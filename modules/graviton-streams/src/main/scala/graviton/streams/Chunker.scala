package graviton.streams

import graviton.core.model.Block
import graviton.core.scan.{FS, FreeScan, Prim}
import graviton.core.scan.FS.*
import graviton.core.types.*
import zio.{Chunk, FiberRef, Unsafe, ZIO}
import zio.stream.ZPipeline

import scala.Conversion

trait Chunker:
  def name: String
  def pipeline: ZPipeline[Any, Throwable, Byte, Block]

object Chunker:
  val default: Chunker = fastCdc()

  val current: FiberRef[Chunker] =
    Unsafe.unsafe(implicit unsafe => FiberRef.unsafe.make(default))

  def locally[R, E, A](chunker: Chunker)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    current.locally(chunker)(effect)

  def fixed(size: ChunkSize, label: Option[String] = None): Chunker =
    val sizeBytes = size
    val pipeline  = pipelineFromScan(FS.fixedChunker(sizeBytes))
    SimpleChunker(label.getOrElse(s"fixed-$sizeBytes"), pipeline)

  def fastCdc(config: FastCDC.Config = FastCDC.Config.Default, label: Option[String] = None): Chunker =
    val normalized = FastCDC.Config.sanitize(config)
    val pipeline   = FastCDC.chunker(normalized)
    SimpleChunker(label.getOrElse(s"fastcdc-${normalized.avgBytes}"), pipeline)

  private[streams] def pipelineFromScan(scan: FreeScan[Prim, Chunk[Byte], Chunk[Byte]]): ZPipeline[Any, Throwable, Byte, Block] =
    val optimized = scan.optimize.toPipeline
    (ZPipeline.identity[Byte].chunks >>> optimized)
      .mapChunksZIO { chunkOfBlocks =>
        ZIO
          .foreach(chunkOfBlocks) { bytes =>
            ZIO.fromEither(Block.fromChunk(bytes)).mapError(msg => new IllegalArgumentException(msg))
          }
          .map(blocks => Chunk.fromIterable(blocks))
      }

  given chunkerToPipeline: Conversion[Chunker, ZPipeline[Any, Throwable, Byte, Block]] with
    def apply(chunker: Chunker): ZPipeline[Any, Throwable, Byte, Block] =
      chunker.pipeline

private final case class SimpleChunker(
  name: String,
  pipeline: ZPipeline[Any, Throwable, Byte, Block],
) extends Chunker
