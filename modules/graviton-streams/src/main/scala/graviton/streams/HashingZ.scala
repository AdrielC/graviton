package graviton.streams

import graviton.core.bytes.{Hasher, MultiHasher}
import zio.ZIO
import zio.stream.{ZPipeline, ZSink}

object HashingZ:
  def sink(hasher: Hasher): ZSink[Any, Nothing, Byte, Nothing, Either[String, Hasher]] =
    ZSink
      .foldLeft(hasher) { (h, byte: Byte) =>
        val _ = h.update(Array(byte))
        h
      }
      .map(_.result.map(_ => hasher))

  def pipeline(multi: MultiHasher): ZPipeline[Any, Nothing, Byte, Byte] =
    ZPipeline.mapChunksZIO { chunk =>
      ZIO.attempt(multi.update(chunk.toArray)).orDie.as(chunk)
    }
