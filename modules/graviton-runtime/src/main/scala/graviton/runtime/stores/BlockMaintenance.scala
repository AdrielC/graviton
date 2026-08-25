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
  def inventory: ZStream[Any, Throwable, BlockInventoryEntry]
  def quarantine(entry: BlockInventoryEntry): Task[QuarantinedBlock]
  def restore(block: QuarantinedBlock): Task[Unit]
  def purge(block: QuarantinedBlock): Task[Unit]
