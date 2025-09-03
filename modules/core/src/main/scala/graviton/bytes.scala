package graviton

import zio.stream.*

opaque type Bytes <: ZStream[Any, Throwable, Byte] =
  ZStream[Any, Throwable, Byte]

object Bytes:
  inline def apply(stream: ZStream[Any, Throwable, Byte]): Bytes = stream
