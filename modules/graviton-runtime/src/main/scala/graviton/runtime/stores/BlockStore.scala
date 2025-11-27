package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.runtime.model.{BlockBatchResult, BlockWritePlan, CanonicalBlock}
import zio.*
import zio.stream.*

trait BlockStore:
  type BlockSink = ZSink[Any, Throwable, CanonicalBlock, Nothing, BlockBatchResult]

  /** Persist canonical blocks produced by the chunker + hashing pipeline. */
  def putBlocks(plan: BlockWritePlan = BlockWritePlan()): BlockSink

  /** Stream the bytes for a previously stored canonical block. */
  def get(key: BinaryKey.Block): ZStream[Any, Throwable, Byte]

  /** Return whether a canonical block already exists in the configured store. */
  def exists(key: BinaryKey.Block): ZIO[Any, Throwable, Boolean]
