package graviton.streams

import zio.stream.ZPipeline

object Chunker:
  def fixed(size: Int): ZPipeline[Any, Nothing, Byte, Byte] =
    require(size > 0, "chunk size must be positive")
    ZPipeline.rechunk(size)
