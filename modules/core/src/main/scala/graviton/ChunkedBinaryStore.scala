package graviton

import graviton.core.BinaryAttributes

import zio.stream.*

trait ChunkedBinaryStore:

  def insertChunks(
    attrs: BinaryAttributes
  ): ZSink[Any, Throwable, ChunkDef, Nothing, InsertChunkResult]
