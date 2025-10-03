package graviton.core

import zio.*
import zio.stream.*

trait BlockStore extends FileStore:
  def ingestBlocks(
    chunker: ZSink[Any, Throwable, Byte, Byte, Chunk[FileKey.CasKey]]
  ): ZSink[Any, Throwable, Byte, Byte, Chunk[FileKey.CasKey]]
  def readBlock(key: FileKey.CasKey): IO[Throwable, Option[Bytes]]
