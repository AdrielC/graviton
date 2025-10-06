package graviton
package chunking

import zio.*
import zio.stream.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.collection.Length
import io.github.iltotore.iron.{zio as _, *}
import scala.compiletime.ops.int.{`*`}
import scala.compiletime.ops.any.ToString
import scala.compiletime.ops.string.{`+`}
import io.github.iltotore.iron.constraint.any.DescribedAs

type Stream[+A] = zio.stream.ZStream[Any, Throwable, A]
type Pipeline[-I, +O] = zio.stream.ZPipeline[Any, Throwable, I, O]
type Channel[-I, +W, +O] = zio.stream.ZChannel[Any, Throwable, I, Any, Throwable, W, O]

type MAX_BLOCK_SIZE = 1 * 1024 * 1024
final val MAX_BLOCK_SIZE = scala.compiletime.constValue[MAX_BLOCK_SIZE]

type BlockSize = BlockSize.T
object BlockSize extends SubtypeExt[Int, GreaterEqual[1] & LessEqual[MAX_BLOCK_SIZE]]:
    final val ONE_B: BlockSize = assume(1)
    final val ONE_KB: BlockSize = assume(ONE_B * 1024)
    final val ONE_MB: BlockSize = assume(ONE_KB * 1024)

    final val min: BlockSize = ONE_B
    final val max: BlockSize = assume(MAX_BLOCK_SIZE)

    type Desc = DescribedAs[
        Length[GreaterEqual[1]] & Length[LessEqual[MAX_BLOCK_SIZE]],
        "BlockSize must be between 1 and " + ToString[MAX_BLOCK_SIZE],
    ]

type Block = Block.T
object Block extends SubtypeExt[Chunk[Byte], BlockSize.Desc]:

    def apply(chunk: Chunk[Byte]): Chunk[Block] =
        val (head, tail) = chunk.splitAt(BlockSize.max) 
        val headBlock = assume(head)
        Option(tail)
        .filter(_.nonEmpty)
        .fold(Chunk.single(headBlock))(
            tail => headBlock +: apply(tail)
        )


opaque type BlockStream[R, E] <: ZStream[R, E, Block] = ZStream[R, E, Block]
object BlockStream:

    val sink: ZSink[Any, Nothing, Chunk[Byte], Chunk[Byte], Chunk[Block]] = 
        ZSink.foldWeightedDecompose[Chunk[Byte], Chunk[Block]](
            Chunk.empty[Block],
        )( 
            (acc, next) => acc.length + next.length,
            BlockSize.max,
            (chunk) => Block(chunk)
        )(
            (acc, next) => acc ++ Block(next)
        )

    def apply[R, E](stream: ZStream[R, E, Byte]): BlockStream[R, E] = 
        Block.assumeAll:
            stream
                        .chunks
                        .filter(_.nonEmpty)
                        .transduce(sink)
                        .flattenChunks
    
