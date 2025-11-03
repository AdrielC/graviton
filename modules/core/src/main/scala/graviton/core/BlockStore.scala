package graviton
package core

import zio.*
import graviton.core.model.*


trait BlockStore extends FileStore:

  def putBlock(block: Block): ZIO[Any, Throwable, BlockKey]

  def getBlock(key: BlockKey, range: Option[ByteRange] = None): IO[Throwable, Option[Block]]

  def readBlocks(key: FileKey): IO[Throwable, Option[Blocks]]
