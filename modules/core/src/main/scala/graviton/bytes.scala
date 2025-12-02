package graviton

import zio.{NonEmptyChunk, Chunk}
import zio.stream.*
import graviton.core.model.Block

import java.nio.charset.StandardCharsets

type ByteStream  = ZStream[Any, Throwable, Byte]
type BytesStream = ZStream[Any, Throwable, Bytes]
type BlockStream = ZStream[Any, Throwable, Block]

extension (stream: BytesStream) def flattenBytes: Bytes = Bytes(stream.flatten)

opaque type Bytes <: ByteStream = ByteStream

object Bytes:
  inline def apply(stream: ByteStream | BytesStream | NonEmptyChunk[Byte] | Chunk[Byte] | String): Bytes =
    inline compiletime.erasedValue[stream.type] match
      case _: ByteStream                      => stream.asInstanceOf[ByteStream]
      case _: BytesStream                     => stream.asInstanceOf[BytesStream].flattenBytes
      case _: Chunk[Byte] @unchecked          => ZStream.fromChunk(stream.asInstanceOf[Chunk[Byte]])
      case _: NonEmptyChunk[Byte] @unchecked  => ZStream.fromChunk(stream.asInstanceOf[NonEmptyChunk[Byte]].toChunk)
      case ""                                 => compiletime.error("Bytes.apply: string must not be empty")
      case _: String                          => ZStream.fromIterable(stream.asInstanceOf[String].getBytes(StandardCharsets.UTF_8))
    end match
  end apply

opaque type Blocks <: BlockStream = BlockStream

object Blocks:
  inline def apply(stream: BlockStream): Blocks = stream
