package graviton.streams

import zio.stream.ZPipeline

object Chunker:
  def fixed(size: Int): ZPipeline[Any, Nothing, Byte, Byte] =
    ZPipeline.identity[Byte]
