package graviton.runtime.stores

import graviton.runtime.admission.{
  AdmissionFencingToken,
  AdmissionLeaseId,
  AdmissionOccupancy,
  AdmissionPolicyVersion,
  DistributedAdmission,
  DistributedAdmissionLease,
  DistributedAdmissionRequest,
}
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
    test("attributes download admission failures to the read operation") {
      for
        budget <- TransferBudget.make(TransferMemoryConfig(TransferMemoryLimit.applyUnsafe(Capacity)))
        exit   <- ZIO.scoped(budget.reserveScoped(StoreOperation.GetBlob, Capacity + 1L)).exit
      yield assertTrue(
        exit.causeOption.flatMap(_.failureOption).exists {
          case error: StoreError.CapacityExceeded => error.operation == StoreOperation.GetBlob
          case _                                  => false
        }
      )
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
    test("holds hard local permits before requesting cluster admission and releases both with the scope") {
      val tenant = TenantId.applyUnsafe("00000000-0000-4000-8000-000000000001")
      val scope  = TransferScope(Some(tenant), StoreBackend.S3)
      for
        requested    <- Promise.make[Nothing, DistributedAdmissionRequest]
        allow        <- Promise.make[Nothing, Unit]
        released     <- Ref.make(0)
        distributed   = recordingAdmission(requested, allow, released)
        budget       <- TransferBudget.make(
                          TransferMemoryConfig(TransferMemoryLimit.applyUnsafe(Capacity)),
                          admission(tenantBytes = Capacity),
                          distributed,
                          graviton.runtime.metrics.MetricsRegistry.noop,
                        )
        footprint    <- ZIO.fromEither(TransferFootprint.single(TransferComponent.applyUnsafe("test"), 4L * 1024L * 1024L))
        holder       <- ZIO.scoped(budget.reserveScoped(scope, StoreOperation.GetBlob, footprint)).fork
        request      <- requested.await
        during       <- budget.availableBytes
        _            <- allow.succeed(())
        _            <- holder.join
        after        <- budget.availableBytes
        releaseCount <- released.get
      yield assertTrue(
        request.scope == scope,
        request.operation == StoreOperation.GetBlob,
        request.footprint == footprint,
        during == Capacity - footprint.totalBytes,
        after == Capacity,
        releaseCount == 1,
      )
    },
    test("maps cluster admission failure to a typed store error and releases local permits") {
      val distributed = new DistributedAdmission:
        override def acquireScoped(
          request: DistributedAdmissionRequest
        ): ZIO[Scope, DistributedAdmission.Error, DistributedAdmissionLease] =
          val _ = request
          ZIO.fail(DistributedAdmission.Error.Unavailable("coordinator unreachable"))

        override def snapshot(scope: TransferScope) =
          val _ = scope
          ZIO.dieMessage("not used")

      for
        budget    <- TransferBudget.make(
                       TransferMemoryConfig(TransferMemoryLimit.applyUnsafe(Capacity)),
                       admission(),
                       distributed,
                       graviton.runtime.metrics.MetricsRegistry.noop,
                     )
        footprint <- ZIO.fromEither(TransferFootprint.single(TransferComponent.applyUnsafe("test"), 1024L))
        exit      <- ZIO
                       .scoped(budget.reserveScoped(TransferScope.backend(StoreBackend.S3), StoreOperation.PutBlob, footprint))
                       .exit
        after     <- budget.availableBytes
      yield assertTrue(
        exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[StoreError.DistributedAdmissionUnavailable]),
        after == Capacity,
      )
    },
  )

  private def recordingAdmission(
    requested: Promise[Nothing, DistributedAdmissionRequest],
    allow: Promise[Nothing, Unit],
    released: Ref[Int],
  ): DistributedAdmission = new DistributedAdmission:
    override def acquireScoped(
      request: DistributedAdmissionRequest
    ): ZIO[Scope, DistributedAdmission.Error, DistributedAdmissionLease] =
      requested.succeed(request).ignore *>
        allow.await *>
        ZIO.addFinalizer(released.update(_ + 1)) *>
        ZIO.succeed(new DistributedAdmissionLease:
          override val id                   = AdmissionLeaseId.applyUnsafe("test-lease")
          override val fencingToken         = AdmissionFencingToken.applyUnsafe(1L)
          override val policyVersion        = AdmissionPolicyVersion.Initial
          override val occupancyAtAdmission = AdmissionOccupancy(1024L, 1L, Some(1024L), Some(1L), 1L)
          override val revoked              = ZIO.never)

    override def snapshot(scope: TransferScope) =
      val _ = scope
      ZIO.dieMessage("not used")

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
