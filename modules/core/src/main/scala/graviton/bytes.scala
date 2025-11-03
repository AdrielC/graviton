package graviton

import zio.stream.*
import graviton.core.model.Block


opaque type Bytes <: ZStream[Any, Throwable, Byte] =
  ZStream[Any, Throwable, Byte]

object Bytes:
  inline def apply(stream: ZStream[Any, Throwable, Byte]): Bytes = stream



opaque type Blocks <: ZStream[Any, Throwable, Block] =
  ZStream[Any, Throwable, Block]
object Blocks:
  def apply(stream: Bytes): Bytes = stream
  
