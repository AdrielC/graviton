package graviton.runtime.stores

import graviton.runtime.config.TransferMemoryConfig
import zio.*

/**
 * Process-wide weighted budget for buffers retained by active transfers.
 *
 * Reservations are scoped, interruptible while waiting, and released on every
 * exit path. Backends reserve their conservative live-byte ceiling before
 * accepting input, so concurrent uploads cannot multiply memory without bound.
 */
trait TransferBudget:
  def capacityBytes: Long
  def reserveScoped(bytes: Long): ZIO[Scope, StoreError, Unit]
  def availableBytes: UIO[Long]

object TransferBudget:
  val service: ZIO[TransferBudget, Nothing, TransferBudget] = ZIO.service[TransferBudget]

  def make(config: TransferMemoryConfig): UIO[TransferBudget] =
    Semaphore.make(config.maximumBufferedBytes.value).map { semaphore =>
      new TransferBudget:
        override val capacityBytes: Long = config.maximumBufferedBytes.value

        override def reserveScoped(bytes: Long): ZIO[Scope, StoreError, Unit] =
          if bytes <= 0L then ZIO.fail(StoreError.InvalidInput(StoreOperation.PutBlob, "transfer reservation must be positive"))
          else if bytes > capacityBytes then ZIO.fail(StoreError.CapacityExceeded(StoreOperation.PutBlob, capacityBytes, Some(bytes)))
          else semaphore.withPermitsScoped(bytes)

        override def availableBytes: UIO[Long] = semaphore.available
    }

  /** Compatibility only for direct constructors. Production layers use [[live]]. */
  val unbounded: TransferBudget = new TransferBudget:
    override val capacityBytes: Long                                      = Long.MaxValue
    override def reserveScoped(bytes: Long): ZIO[Scope, StoreError, Unit] =
      ZIO.fail(StoreError.InvalidInput(StoreOperation.PutBlob, "transfer reservation must be positive")).when(bytes <= 0L).unit
    override val availableBytes: UIO[Long]                                = ZIO.succeed(Long.MaxValue)

  val live: ZLayer[TransferMemoryConfig, Nothing, TransferBudget] =
    ZLayer.fromZIO(ZIO.serviceWithZIO[TransferMemoryConfig](make))

  val default: ZLayer[Any, Nothing, TransferBudget] =
    ZLayer.fromZIO(make(TransferMemoryConfig.Default))
