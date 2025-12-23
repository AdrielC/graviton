package graviton.streams

import graviton.core.bytes.{Hasher, MultiHasher, Digest, Provider, HashAlgo}
import zio.ZIO
import zio.stream.{ZPipeline, ZSink}

object HashingZ:

  def sink(hasher: Hasher): ZSink[Any, IllegalArgumentException, Byte, Nothing, Hasher] =
    ZSink.foldLeftChunks(hasher) { (h, chunk) => h.update(chunk.toArray) }

  def sink(hashAlgo: HashAlgo): ZSink[Provider, IllegalArgumentException, Byte, Nothing, Hasher] =
    ZSink.unwrap:
      ZIO.serviceWithZIO[Provider](p => 
        ZIO.fromEither(p.getInstance(hashAlgo))
        .mapError(err => IllegalArgumentException(err)))
        .map(sink)

  def pipeline(multi: MultiHasher): ZPipeline[Any, Nothing, Byte, Byte] =
    ZPipeline.mapChunksZIO { chunk =>
      ZIO.attempt(multi.update(chunk.toArray)).orDie.as(chunk)
    }
