package graviton

import zio.*
import zio.stream.*

/** Splits a byte stream into logical chunks. */
trait Chunker:
  def name: String
  def pipeline: ZPipeline[Any, Throwable, Byte, Chunk[Byte]]

object Chunker:

  /** Fixed-size chunking. Last chunk may be smaller. */
  def fixed(size: Int): Chunker =
    new Chunker:
      val name = s"fixed($size)"
      val pipeline: ZPipeline[Any, Throwable, Byte, Chunk[Byte]] =
        ZPipeline.fromChannel:
          def loop(buf: Chunk[Byte]): ZChannel[Any, Throwable, Chunk[
            Byte
          ], Any, Throwable, Chunk[Chunk[Byte]], Any] =
            ZChannel.readWith(
              (in: Chunk[Byte]) =>
                val acc = buf ++ in
                if acc.length >= size then
                  val (full, rest) = acc.splitAt(size)
                  ZChannel.write(Chunk(full)) *> loop(rest)
                else loop(acc)
              ,
              (err: Throwable) => ZChannel.fail(err),
              (_: Any) =>
                if buf.isEmpty then ZChannel.unit
                else ZChannel.write(Chunk(buf))
            )
          loop(Chunk.empty)
