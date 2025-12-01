package graviton

import zio.{NonEmptyChunk, Chunk}
import zio.stream.*
import graviton.core.model.Block

type ByteStream  = ZStream[Any, Throwable, Byte]
type BytesStream = ZStream[Any, Throwable, Bytes]
type BlockStream = ZStream[Any, Throwable, Block]

extension (stream: BytesStream) def flattenBytes: Bytes = Bytes(stream.flatten)

opaque type Bytes <: ByteStream = ByteStream

object Bytes:
  inline def apply(stream: ByteStream | BytesStream | NonEmptyChunk[Byte] | Chunk[Byte]): Bytes =
    inline stream match
      case s: ByteStream                     => s
      case s: BytesStream                    => s.flattenBytes
      case c: Chunk[Byte] @unchecked         => ZStream.fromChunk(c)
      case n: NonEmptyChunk[Byte] @unchecked => ZStream.fromChunk(n.toChunk)
    end match
  end apply

opaque type Blocks <: BlockStream = BlockStream

object Blocks:
  inline def apply(stream: BlockStream): Blocks = stream
