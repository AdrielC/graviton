package graviton.core

import zio.*
import zio.stream.*

trait BlockStore extends CasStore:
  def ingestBlocks(
    chunker: ZSink[Any, Throwable, Byte, Byte, Chunk[BinaryKey.CasKey]]
  ): ZSink[Any, Throwable, Byte, Byte, Chunk[BinaryKey.CasKey]]
  def readBlock(key: BinaryKey.CasKey): IO[Throwable, Option[Bytes]]
