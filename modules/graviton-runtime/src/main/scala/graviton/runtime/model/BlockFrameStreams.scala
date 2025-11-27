package graviton.runtime.model

import graviton.streams.interop.scodec.ZStreamDecoder
import scodec.bits.BitVector
import zio.*
import zio.stream.*
import zio.ChunkBuilder

object BlockFrameStreams:

  val decode: ZPipeline[Any, Throwable, Byte, BlockFrame] =
    ZPipeline
      .mapChunks[Byte, BitVector](chunk =>
        if chunk.isEmpty then Chunk.empty
        else Chunk.single(BitVector(chunk.toArray))
      )
      .andThen(ZStreamDecoder.many(BlockFrameCodec.codec))

  val encode: ZPipeline[Any, Throwable, BlockFrame, Byte] =
    ZPipeline.mapChunksZIO { frames =>
      ZIO
        .foreach(frames) { frame =>
          ZIO.fromEither(
            BlockFrameCodec.codec
              .encode(frame)
              .toEither
              .left
              .map(err => new IllegalArgumentException(s"Failed to encode frame: ${err.message}"))
          )
        }
        .map { bitVectors =>
          val builder = ChunkBuilder.make[Byte]()
          bitVectors.foreach(bits => builder ++= Chunk.fromArray(bits.toByteArray))
          builder.result()
        }
    }
