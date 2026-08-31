package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import zio.*
import zio.stream.ZStream

import java.time.Instant

final case class BlockInventoryEntry(
  key: BinaryKey.Block,
  size: Long,
  lastModified: Instant,
)

final case class QuarantinedBlock(
  key: BinaryKey.Block,
  token: String,
  size: Long,
  quarantinedAt: Instant,
)

/** Destructive block operations kept separate from the normal CAS surface. */
trait BlockMaintenance:
  def inventory: ZStream[Any, StoreError, BlockInventoryEntry]

  /**
   * Durable, streaming recovery inventory for reversible GC receipts.
   *
   * The empty default preserves the released backend SPI for custom stores
   * that predate recovery inventory. Backends with durable quarantine storage
   * override this method and the backend law kit verifies exact restore.
   */
  def quarantineInventory: ZStream[Any, StoreError, QuarantinedBlock] = ZStream.empty
  def quarantine(entry: BlockInventoryEntry): IO[StoreError, QuarantinedBlock]
  def restore(block: QuarantinedBlock): IO[StoreError, Unit]
  def purge(block: QuarantinedBlock): IO[StoreError, Unit]
