package graviton.streams

import graviton.core.types.*
import zio.stream.ZPipeline

object Chunker:
  def fixed(size: ChunkSize): ZPipeline[Any, Nothing, Byte, Byte] =
    ZPipeline.rechunk(size)
