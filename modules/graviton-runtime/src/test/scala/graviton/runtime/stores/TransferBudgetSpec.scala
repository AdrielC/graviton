package graviton.runtime.stores

import graviton.runtime.config.{TransferMemoryConfig, TransferMemoryLimit}
import zio.*
import zio.test.*

object TransferBudgetSpec extends ZIOSpecDefault:
  private val Capacity = 64L * 1024L * 1024L

  override def spec: Spec[TestEnvironment, Any] = suite("TransferBudget")(
    test("backpressures aggregate reservations until capacity is released") {
      for
        budget   <- TransferBudget.make(TransferMemoryConfig(TransferMemoryLimit.applyUnsafe(Capacity)))
        acquired <- Promise.make[Nothing, Unit]
        release  <- Promise.make[Nothing, Unit]
        holder   <- ZIO
                      .scoped(
                        budget.reserveScoped(48L * 1024L * 1024L) *>
                          acquired.succeed(()).ignore *>
                          release.await
                      )
                      .fork
        _        <- acquired.await
        waiter   <- ZIO.scoped(budget.reserveScoped(32L * 1024L * 1024L)).timeout(1.second).fork
        _        <- TestClock.adjust(1.second)
        timedOut <- waiter.join
        during   <- budget.availableBytes
        _        <- release.succeed(())
        _        <- holder.join
        after    <- budget.availableBytes
      yield assertTrue(timedOut.isEmpty, during == 16L * 1024L * 1024L, after == Capacity)
    },
    test("interruption releases every reserved byte") {
      for
        budget   <- TransferBudget.make(TransferMemoryConfig(TransferMemoryLimit.applyUnsafe(Capacity)))
        acquired <- Promise.make[Nothing, Unit]
        holder   <- ZIO
                      .scoped(
                        budget.reserveScoped(48L * 1024L * 1024L) *>
                          acquired.succeed(()).ignore *>
                          ZIO.never
                      )
                      .fork
        _        <- acquired.await
        during   <- budget.availableBytes
        _        <- holder.interrupt
        after    <- budget.availableBytes
      yield assertTrue(during == 16L * 1024L * 1024L, after == Capacity)
    },
    test("rejects a single transfer larger than the process budget") {
      for
        budget <- TransferBudget.make(TransferMemoryConfig(TransferMemoryLimit.applyUnsafe(Capacity)))
        exit   <- ZIO.scoped(budget.reserveScoped(Capacity + 1L)).exit
      yield assertTrue(exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[StoreError.CapacityExceeded]))
    },
  )
