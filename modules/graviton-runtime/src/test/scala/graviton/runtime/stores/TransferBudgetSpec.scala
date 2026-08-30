package graviton.runtime.stores

import graviton.runtime.config.{
  BackendTransferConcurrency,
  TenantTransferConcurrency,
  TransferAdmissionConfig,
  TransferMemoryConfig,
  TransferMemoryLimit,
}
import graviton.runtime.upload.TenantId
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
    test("composes named transfer owners and rejects arithmetic overflow") {
      val input    = TransferFootprint.single(TransferComponent.applyUnsafe("input"), 4L * 1024L * 1024L)
      val backend  = TransferFootprint.single(TransferComponent.applyUnsafe("backend"), 8L * 1024L * 1024L)
      val total    = for
        left  <- input
        right <- backend
        sum   <- left ++ right
      yield sum
      val huge     = TransferFootprint.single(TransferComponent.applyUnsafe("huge"), 600L * 1024L * 1024L * 1024L)
      val overflow = huge.flatMap(value => value ++ value)

      assertTrue(
        total.exists(_.totalBytes == 12L * 1024L * 1024L),
        total.exists(_.contributions.map(_.component.value) == Chunk("input", "backend")),
        overflow.left.exists(_.isInstanceOf[TransferFootprint.Error.Overflow]),
      )
    },
    test("rejects a transfer above its tenant byte ceiling") {
      val tenant = TenantId.applyUnsafe("00000000-0000-4000-8000-000000000001")
      val config = admission(tenantBytes = Capacity)
      for
        budget    <- TransferBudget.make(TransferMemoryConfig(TransferMemoryLimit.applyUnsafe(Capacity * 2L)), config)
        footprint <- ZIO.fromEither(TransferFootprint.single(TransferComponent.applyUnsafe("test"), Capacity + 1L))
        exit      <- ZIO.scoped(budget.reserveScoped(TransferScope(Some(tenant), StoreBackend.S3), footprint)).exit
      yield assertTrue(
        exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[StoreError.TenantTransferCapacityExceeded])
      )
    },
    test("isolates tenant concurrency without blocking another tenant") {
      val tenantA = TenantId.applyUnsafe("00000000-0000-4000-8000-000000000001")
      val tenantB = TenantId.applyUnsafe("00000000-0000-4000-8000-000000000002")
      val config  = admission(tenantConcurrency = 1, backendConcurrency = 4)
      for
        budget      <- TransferBudget.make(TransferMemoryConfig(TransferMemoryLimit.applyUnsafe(Capacity * 2L)), config)
        footprint   <- ZIO.fromEither(TransferFootprint.single(TransferComponent.applyUnsafe("test"), 1024L))
        entered     <- Promise.make[Nothing, Unit]
        release     <- Promise.make[Nothing, Unit]
        sameEntered <- Promise.make[Nothing, Unit]
        holder      <- ZIO
                         .scoped(
                           budget.reserveScoped(TransferScope(Some(tenantA), StoreBackend.S3), footprint) *>
                             entered.succeed(()) *>
                             release.await
                         )
                         .fork
        _           <- entered.await
        same        <- ZIO
                         .scoped(
                           budget.reserveScoped(TransferScope(Some(tenantA), StoreBackend.Filesystem), footprint) *>
                             sameEntered.succeed(())
                         )
                         .fork
        other       <- ZIO.scoped(budget.reserveScoped(TransferScope(Some(tenantB), StoreBackend.Filesystem), footprint)).exit
        blocked     <- sameEntered.poll
        _           <- release.succeed(())
        _           <- holder.join
        _           <- same.join
        completed   <- sameEntered.poll
      yield assertTrue(other.isSuccess, blocked.isEmpty, completed.nonEmpty)
    },
    test("limits backend concurrency across tenants and releases it on interruption") {
      val tenantA = TenantId.applyUnsafe("00000000-0000-4000-8000-000000000001")
      val tenantB = TenantId.applyUnsafe("00000000-0000-4000-8000-000000000002")
      val config  = admission(tenantConcurrency = 4, backendConcurrency = 1)
      for
        budget      <- TransferBudget.make(TransferMemoryConfig(TransferMemoryLimit.applyUnsafe(Capacity * 2L)), config)
        footprint   <- ZIO.fromEither(TransferFootprint.single(TransferComponent.applyUnsafe("test"), 1024L))
        entered     <- Promise.make[Nothing, Unit]
        secondReady <- Promise.make[Nothing, Unit]
        holder      <- ZIO
                         .scoped(
                           budget.reserveScoped(TransferScope(Some(tenantA), StoreBackend.S3), footprint) *>
                             entered.succeed(()) *>
                             ZIO.never
                         )
                         .fork
        _           <- entered.await
        second      <- ZIO
                         .scoped(
                           budget.reserveScoped(TransferScope(Some(tenantB), StoreBackend.S3), footprint) *>
                             secondReady.succeed(())
                         )
                         .fork
        blocked     <- secondReady.poll
        _           <- holder.interrupt
        _           <- second.join
        completed   <- secondReady.poll
        available   <- budget.availableBytes
      yield assertTrue(blocked.isEmpty, completed.nonEmpty, available == Capacity * 2L)
    },
    test("fails closed when every bounded tenant registry entry is active") {
      val tenantA = TenantId.applyUnsafe("00000000-0000-4000-8000-000000000001")
      val tenantB = TenantId.applyUnsafe("00000000-0000-4000-8000-000000000002")
      val config  = admission(maximumResidentTenants = 1)
      for
        budget    <- TransferBudget.make(TransferMemoryConfig(TransferMemoryLimit.applyUnsafe(Capacity * 2L)), config)
        footprint <- ZIO.fromEither(TransferFootprint.single(TransferComponent.applyUnsafe("test"), 1024L))
        entered   <- Promise.make[Nothing, Unit]
        holder    <- ZIO
                       .scoped(
                         budget.reserveScoped(TransferScope(Some(tenantA), StoreBackend.S3), footprint) *>
                           entered.succeed(()) *>
                           ZIO.never
                       )
                       .fork
        _         <- entered.await
        exit      <- ZIO.scoped(budget.reserveScoped(TransferScope(Some(tenantB), StoreBackend.S3), footprint)).exit
        _         <- holder.interrupt
      yield assertTrue(
        exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[StoreError.TransferAdmissionSaturated])
      )
    },
  )

  private def admission(
    tenantBytes: Long = Capacity,
    tenantConcurrency: Int = 2,
    backendConcurrency: Int = 2,
    maximumResidentTenants: Int = 16,
  ): TransferAdmissionConfig =
    TransferAdmissionConfig(
      maximumTenantBufferedBytes = TransferMemoryLimit.applyUnsafe(tenantBytes),
      maximumConcurrentTenantTransfers = TenantTransferConcurrency.applyUnsafe(tenantConcurrency),
      maximumConcurrentBackendTransfers = BackendTransferConcurrency.applyUnsafe(backendConcurrency),
      maximumResidentTenants = maximumResidentTenants,
      maximumResidentBackends = 8,
      acquisitionTimeout = 5.seconds,
    )
