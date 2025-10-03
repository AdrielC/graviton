package graviton.streams.chunking

import graviton.core.model.Block
import zio.*
import zio.stream.*

/** Simple fixed-size chunker. */
object FixedChunker:
  def apply(size: Int): Chunker =
    new Chunker:
      val name                                             = s"fixed($size)"
      val pipeline: ZPipeline[Any, Throwable, Byte, Block] =
        ZPipeline
          .fromChannel {
            def splitAll(acc: Chunk[Byte]): (Chunk[Chunk[Byte]], Chunk[Byte]) =
              var rest = acc
              val outs = scala.collection.mutable.ListBuffer.empty[Chunk[Byte]]
              while rest.length >= size do
                val (full, r) = rest.splitAt(size)
                outs += full
                rest = r
              (Chunk.fromIterable(outs.toList), rest)

            def loop(buf: Chunk[Byte]): ZChannel[Any, Throwable, Chunk[
              Byte
            ], Any, Throwable, Chunk[Chunk[Byte]], Any] =
              ZChannel.readWith(
                (in: Chunk[Byte]) =>
                  val (emitted, leftover) = splitAll(buf ++ in)
                  ZChannel.write(emitted) *> loop(leftover)
                ,
                (err: Throwable) => ZChannel.fail(err),
                (_: Any) =>
                  if buf.isEmpty then ZChannel.unit
                  else ZChannel.write(Chunk(buf)),
              )
            loop(Chunk.empty)
          }
          .mapChunksZIO { chunked =>
            ZIO.foreach(chunked)(bytes => ZIO.fromEither(Block.fromChunk(bytes)).mapError(err => new IllegalArgumentException(err)))
          }
