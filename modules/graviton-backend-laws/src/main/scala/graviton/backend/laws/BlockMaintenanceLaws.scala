package graviton.backend.laws

import graviton.core.bytes.HashAlgo
import graviton.core.attributes.BinaryAttributes
import graviton.core.keys.BinaryKey
import graviton.runtime.model.CanonicalBlock
import graviton.runtime.stores.*
import zio.*
import zio.test.*

/** Reusable reversible-GC and recovery-inventory contract for block backends. */
object BlockMaintenanceLaws:
  type Subject = RepairableBlockStore & BlockMaintenance

  def suite(backendName: String)(acquire: ZIO[Scope, StoreError, Subject]): Spec[TestEnvironment, StoreError] =
    zio.test.suite(s"$backendName BlockMaintenance laws")(
      zio.test.test("quarantine inventory supports exact restore and purge") {
        ZIO.scoped {
          for
            subject       <- acquire
            first         <- fixture("quarantine-restore")
            second        <- fixture("quarantine-purge")
            _             <- subject.putBlock(first)
            _             <- subject.putBlock(second)
            firstEntry    <- subject.inventory
                               .filter(_.key == first.key)
                               .runHead
                               .someOrFail(StoreError.NotFound(StoreOperation.InventoryBlocks, first.key))
            firstReceipt  <- subject.quarantine(firstEntry)
            receiptFound  <- subject.quarantineInventory.filter(_.token == firstReceipt.token).runHead
            _             <- subject.restore(firstReceipt)
            restored      <- subject.exists(first.key)
            afterRestore  <- subject.quarantineInventory.filter(_.token == firstReceipt.token).runHead
            secondEntry   <- subject.inventory
                               .filter(_.key == second.key)
                               .runHead
                               .someOrFail(StoreError.NotFound(StoreOperation.InventoryBlocks, second.key))
            secondReceipt <- subject.quarantine(secondEntry)
            _             <- subject.purge(secondReceipt)
            purged        <- subject.exists(second.key)
            afterPurge    <- subject.quarantineInventory.filter(_.token == secondReceipt.token).runHead
          yield assertTrue(
            receiptFound.nonEmpty,
            restored,
            afterRestore.isEmpty,
            !purged,
            afterPurge.isEmpty,
          )
        }
      }
    ) @@ TestAspect.sequential @@ TestAspect.withLiveClock

  private def fixture(value: String): IO[StoreError, CanonicalBlock] =
    val bytes = Chunk.fromArray(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    for
      bits  <- ZIO.fromEither(HashAlgo.Sha256(bytes)).mapError(StoreError.InvalidInput(StoreOperation.PutBlock, _))
      key   <- ZIO.fromEither(BinaryKey.block(bits)).mapError(StoreError.InvalidInput(StoreOperation.PutBlock, _))
      block <- ZIO
                 .fromEither(CanonicalBlock.make(key, bytes, BinaryAttributes.empty))
                 .mapError(StoreError.InvalidInput(StoreOperation.PutBlock, _))
    yield block
