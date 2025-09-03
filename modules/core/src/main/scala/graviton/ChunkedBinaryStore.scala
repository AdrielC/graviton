package graviton

import graviton.core.BinaryAttributes
import zio.*
import zio.stream.*

trait ChunkedBinaryStore:
  def insertChunks(
      attrs: BinaryAttributes,
      defn: ChunkDef
  ): ZSink[Any, Throwable, Byte, Nothing, InsertChunkResult]
