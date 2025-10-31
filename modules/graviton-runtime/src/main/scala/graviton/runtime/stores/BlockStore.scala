package graviton.runtime.stores

import graviton.core.attributes.BinaryAttributes
import graviton.core.keys.BinaryKey
import zio.*
import zio.stream.*

trait BlockStore:
  def storeBlock(attrs: BinaryAttributes): ZSink[Any, Throwable, Byte, Chunk[Byte], BinaryKey.Block]
  def get(key: BinaryKey.Block): ZStream[Any, Throwable, Byte]
  def exists(key: BinaryKey.Block): ZIO[Any, Throwable, Boolean]
