package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.runtime.model.{BlockBatchResult, BlockStoredStatus, BlockWritePlan, CanonicalBlock, StoredBlock}
import zio.*
import zio.stream.*

trait BlockStore:
  type BlockSink = ZSink[Any, StoreError, CanonicalBlock, Nothing, BlockBatchResult]

  /** Persist canonical blocks produced by the chunker + hashing pipeline. */
  def putBlocks(plan: BlockWritePlan = BlockWritePlan()): BlockSink

  /**
   * Persist one already-bounded canonical block.
   *
   * CAS ingest uses this operation so a backend never has to retain payloads or
   * a whole-blob batch result while the source is still arriving. Backends can
   * override it with their native single-block write. The default remains
   * source-compatible for third-party implementations and is bounded to one
   * [[CanonicalBlock]].
   */
  def putBlock(
    block: CanonicalBlock,
    plan: BlockWritePlan = BlockWritePlan(),
  ): IO[StoreError, StoredBlock] =
    ZStream
      .succeed(block)
      .run(putBlocks(plan))
      .flatMap(result =>
        ZIO
          .fromOption(result.stored.headOption)
          .mapError(_ => StoreError.CorruptData(StoreOperation.PutBlock, "Block store completed without a stored-block result"))
      )

  /** Stream the bytes for a previously stored canonical block. */
  def get(key: BinaryKey.Block): ZStream[Any, StoreError, Byte]

  /** Return whether a canonical block already exists in the configured store. */
  def exists(key: BinaryKey.Block): IO[StoreError, Boolean]

  /** Verify that the backing block service is reachable and writable/readable. */
  def healthCheck: IO[StoreError, Unit] = ZIO.unit

object BlockStore:
  val service: ZIO[BlockStore, Nothing, BlockStore] = ZIO.service[BlockStore]

/**
 * A block backend that can atomically replace a missing or corrupt replica
 * with bytes already validated against its content key.
 */
trait RepairableBlockStore extends BlockStore:
  def repairBlock(block: CanonicalBlock): IO[StoreError, Unit]

/** A block store whose background scrub can restore its configured durability. */
trait ConvergentBlockStore extends BlockStore:
  def converge(key: BinaryKey.Block): IO[StoreError, RepairConvergence]

final case class RepairConvergence(
  healthyCopies: Int,
  repairedCopies: Int,
  failedCopies: Map[String, String],
):
  def underProtected: Boolean = failedCopies.nonEmpty

/** Physical storage for one deterministic shard of a canonical block. */
trait ErasureFragmentStore:
  def name: String
  def failureDomain: String
  def put(key: BinaryKey.Block, fragment: graviton.runtime.model.ErasureFragment): IO[StoreError, BlockStoredStatus]
  def get(key: BinaryKey.Block, index: Int, expectedLength: Int): IO[StoreError, graviton.runtime.model.ErasureFragment]
  def repair(key: BinaryKey.Block, fragment: graviton.runtime.model.ErasureFragment): IO[StoreError, Unit]
  def healthCheck: IO[StoreError, Unit]
