package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.runtime.model.{BlockBatchResult, BlockWritePlan, CanonicalBlock}
import zio.*
import zio.stream.*

trait BlockStore:
  def putBlocks(plan: BlockWritePlan = BlockWritePlan()): ZSink[Any, Throwable, CanonicalBlock, Nothing, BlockBatchResult]
  def get(key: BinaryKey.Block): ZStream[Any, Throwable, Byte]
  def exists(key: BinaryKey.Block): ZIO[Any, Throwable, Boolean]
