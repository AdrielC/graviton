package graviton.runtime.model

import graviton.streams.interop.scodec.ZStreamDecoder
import scodec.bits.BitVector
import zio.*
import zio.stream.*

object BlockFrameStreams:
  import BlockFrameWireBounds.*

  private val InputChunkBytes         = StreamChunkBytes
  private[model] val OutputChunkBytes = StreamChunkBytes
  private val OutputChunkBits         = OutputChunkBytes.toLong * 8L

  val decode: ZPipeline[Any, Throwable, Byte, BlockFrame] =
    splitOversizedChunks
      .andThen(
        ZPipeline.mapChunks[Byte, BitVector](chunk =>
          if chunk.isEmpty then Chunk.empty
          else Chunk.single(BitVector.view(chunk.toArray))
        )
      )
      .andThen(ZStreamDecoder.manyByteAligned(BlockFrameCodec.codec))

  val encode: ZPipeline[Any, Throwable, BlockFrame, Byte] =
    ZPipeline
      .rechunk[BlockFrame](1)
      .andThen(
        ZPipeline.mapZIO { (frame: BlockFrame) =>
          ZIO.fromEither(
            BlockFrameCodec.codec
              .encode(frame)
              .toEither
              .left
              .map(_.message)
              .left
              .map(message => new IllegalArgumentException(s"Failed to encode frame: $message"))
          )
        }
      )
      .andThen(packBits)

  /**
   * Splits a large producer chunk into fixed-size views without waiting to fill
   * a smaller producer chunk. Unlike `rechunk`, this preserves first-frame
   * latency for live or long-lived streams.
   */
  private def splitOversizedChunks: ZPipeline[Any, Nothing, Byte, Byte] =
    ZPipeline.fromChannel {
      def emitSlices(input: Chunk[Byte]): ZChannel[Any, Nothing, Any, Any, Nothing, Chunk[Byte], Unit] =
        if input.isEmpty then ZChannel.unit
        else
          val (head, tail) = input.splitAt(InputChunkBytes)
          ZChannel.write(head) *> emitSlices(tail)

      lazy val loop: ZChannel[Any, Nothing, Chunk[Byte], Any, Nothing, Chunk[Byte], Unit] =
        ZChannel.readWith(
          input => emitSlices(input) *> loop,
          error => ZChannel.fail(error),
          _ => ZChannel.unit,
        )

      loop
    }

  private def packBits: ZPipeline[Any, Nothing, BitVector, Byte] =
    ZPipeline.fromChannel {
      def loop(remainder: BitVector): ZChannel[Any, Nothing, Chunk[BitVector], Any, Nothing, Chunk[Byte], Unit] =
        ZChannel.readWith(
          input =>
            val combined     = input.foldLeft(remainder)(_ ++ _)
            val completeSize = combined.size - (combined.size % 8L)
            val complete     = combined.take(completeSize)
            val next         = combined.drop(completeSize)
            write(complete) *> loop(next)
          ,
          error => ZChannel.fail(error),
          _ => write(remainder),
        )

      loop(BitVector.empty)
    }

  private def write(bits: BitVector): ZChannel[Any, Nothing, Any, Any, Nothing, Chunk[Byte], Unit] =
    if bits.isEmpty then ZChannel.unit
    else
      val head = bits.take(OutputChunkBits)
      val tail = bits.drop(OutputChunkBits)
      ZChannel.write(Chunk.fromArray(head.toByteArray)) *> write(tail)

  /** Retained as the package-level compatibility entry point for tests/callers. */
  private[model] def validateForEncoding(frame: BlockFrame): Either[String, Unit] =
    BlockFrameCodec.validateForEncoding(frame)
