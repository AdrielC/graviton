package graviton.streams

import graviton.core.bytes.{Hasher, MultiHasher, Digest}
import zio.ZIO
import zio.stream.{ZPipeline, ZSink}

object HashingZ:

  def sink(hasher: Hasher): ZSink[Any, IllegalArgumentException, Byte, Nothing, Digest] =
    ZSink
      .foldLeft(hasher) { (h, byte: Byte) =>
        val _ = h.update(Array(byte))
        h
      }
      .mapZIO(h =>
        ZIO
          .fromEither(h.digest)
          .mapError(err => IllegalArgumentException(err))
      )

  def pipeline(multi: MultiHasher): ZPipeline[Any, Nothing, Byte, Byte] =
    ZPipeline.mapChunksZIO { chunk =>
      ZIO.attempt(multi.update(chunk.toArray)).orDie.as(chunk)
    }
