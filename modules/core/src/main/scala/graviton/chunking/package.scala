package graviton
package chunking

import zio.*
import zio.stream.*

import graviton.core.model.Block
import graviton.core.model.BlockBuilder

import graviton.core.model.BlockSize

type Stream[+A]          = zio.stream.ZStream[Any, Throwable, A]
type Pipeline[-I, +O]    = zio.stream.ZPipeline[Any, Throwable, I, O]
type Channel[-I, +W, +O] = zio.stream.ZChannel[Any, Throwable, I, Any, Throwable, W, O]

opaque type Bytes[R, E] <: ZStream[R, E, Block] = ZStream[R, E, Block]
object Bytes:

  val sink: ZSink[Any, Nothing, Chunk[Byte], Chunk[Byte], Chunk[Block]] =
    ZSink.foldWeightedDecompose[Chunk[Byte], Chunk[Block]](
      Chunk.empty[Block]
    )(
      (acc, next) => acc.length + next.length,
      BlockSize.Max,
      (chunk) => BlockBuilder.chunkify(chunk),
    )((acc, next) => acc ++ BlockBuilder.chunkify(next))

  def apply[R, E](stream: ZStream[R, E, Byte]): Bytes[R, E] =
    Block.assumeAll:
      stream.chunks
        .filter(_.nonEmpty)
        .transduce(sink)
        .flattenChunks
