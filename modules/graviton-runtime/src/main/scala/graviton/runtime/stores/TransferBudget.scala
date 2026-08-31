package graviton.runtime.stores

import graviton.runtime.admission.{DistributedAdmission, DistributedAdmissionRequest}
import graviton.runtime.config.{TransferAdmissionConfig, TransferMemoryConfig}
import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import graviton.runtime.upload.TenantId
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
  def reserveScoped(operation: StoreOperation, bytes: Long): ZIO[Scope, StoreError, Unit]                  =
    val _ = operation
    reserveScoped(bytes)
  def reserveScoped(footprint: TransferFootprint): ZIO[Scope, StoreError, Unit]                            =
    reserveScoped(footprint.totalBytes)
  def reserveScoped(scope: TransferScope, footprint: TransferFootprint): ZIO[Scope, StoreError, Unit]      =
    val _ = scope
    reserveScoped(footprint)
  def reserveScoped(operation: StoreOperation, footprint: TransferFootprint): ZIO[Scope, StoreError, Unit] =
    reserveScoped(operation, footprint.totalBytes)
  def reserveScoped(
    scope: TransferScope,
    operation: StoreOperation,
    footprint: TransferFootprint,
  ): ZIO[Scope, StoreError, Unit] =
    val _ = operation
    reserveScoped(scope, footprint)
  def bind(scope: TransferScope): TransferBudget                                                           = TransferBudget.bound(this, scope)
  def availableBytes: UIO[Long]

object TransferBudget:
  val service: ZIO[TransferBudget, Nothing, TransferBudget] = ZIO.service[TransferBudget]

  def make(config: TransferMemoryConfig): UIO[TransferBudget] =
    make(config, TransferAdmissionConfig.Default)

  def make(config: TransferMemoryConfig, admission: TransferAdmissionConfig): UIO[TransferBudget] =
    make(config, admission, DistributedAdmission.disabled, MetricsRegistry.noop)

  def make(
    config: TransferMemoryConfig,
    admission: TransferAdmissionConfig,
    distributed: DistributedAdmission,
    metrics: MetricsRegistry,
  ): UIO[TransferBudget] =
    ZIO.dieMessage("invalid transfer admission configuration").unless(admission.validate.isRight) *>
      (for
        process  <- Semaphore.make(config.maximumBufferedBytes.value)
        tenants  <- Ref.Synchronized.make(Map.empty[TenantId, WeightedEntry])
        backends <- Ref.Synchronized.make(Map.empty[StoreBackend, ConcurrentEntry])
      yield new Live(config.maximumBufferedBytes.value, process, tenants, backends, admission, distributed, metrics))

  private final class Live(
    override val capacityBytes: Long,
    process: Semaphore,
    tenants: Ref.Synchronized[Map[TenantId, WeightedEntry]],
    backends: Ref.Synchronized[Map[StoreBackend, ConcurrentEntry]],
    admission: TransferAdmissionConfig,
    distributed: DistributedAdmission,
    metrics: MetricsRegistry,
  ) extends TransferBudget:

    override def reserveScoped(bytes: Long): ZIO[Scope, StoreError, Unit] =
      reserveScoped(StoreOperation.PutBlob, bytes)

    override def reserveScoped(operation: StoreOperation, bytes: Long): ZIO[Scope, StoreError, Unit] =
      TransferFootprint
        .single(TransferComponent.applyUnsafe("unscoped-transfer"), bytes)
        .fold(
          error => ZIO.fail(StoreError.InvalidInput(operation, error.getMessage)),
          footprint => reserveScoped(TransferScope.backend(StoreBackend.Runtime), operation, footprint),
        )

    override def reserveScoped(scope: TransferScope, footprint: TransferFootprint): ZIO[Scope, StoreError, Unit] =
      reserveScoped(scope, StoreOperation.PutBlob, footprint)

    override def reserveScoped(
      scope: TransferScope,
      operation: StoreOperation,
      footprint: TransferFootprint,
    ): ZIO[Scope, StoreError, Unit] =
      val bytes   = footprint.totalBytes
      val acquire = for
        started <- Clock.nanoTime
        _       <- validate(operation, bytes)
        _       <- process.withPermitsScoped(bytes)
        _       <- ZIO.foreachDiscard(scope.tenantId)(tenant => tenantPermitsScoped(tenants, tenant, operation, bytes, admission))
        _       <- backendPermitScoped(backends, scope.backend, operation, admission)
        lease   <- distributed
                     .acquireScoped(DistributedAdmissionRequest(scope, operation, footprint))
                     .mapError(toStoreError(operation))
        waited  <- Clock.nanoTime.map(now => math.max(0L, now - started))
        _       <- metrics.counter(
                     MetricKeys.TransferAdmissionTotal,
                     Map("outcome" -> "admitted", "operation" -> operation.toString, "backend" -> scope.backend.value),
                   )
        _       <- metrics.histogram(
                     MetricKeys.TransferAdmissionWait,
                     waited.toDouble / 1000000000.0,
                     Map("outcome" -> "admitted", "operation" -> operation.toString),
                   )
        _       <- process.available.flatMap(available => metrics.gauge(MetricKeys.TransferBudgetAvailableBytes, available.toDouble, Map.empty))
        _       <- lease.revoked.catchAll { error =>
                     metrics.counter(
                       MetricKeys.DistributedAdmissionLeaseLoss,
                       Map("operation" -> operation.toString, "backend" -> scope.backend.value),
                     ) *> ZIO.logError(error.getMessage)
                   }.forkScoped
      yield ()

      acquire
        .timeoutFail(
          StoreError.TransferAdmissionTimedOut(
            operation,
            scope.backend,
            scope.tenantId,
            bytes,
            admission.acquisitionTimeout,
          )
        )(admission.acquisitionTimeout)
        .tapError { error =>
          metrics.counter(
            MetricKeys.TransferAdmissionTotal,
            Map("outcome" -> "rejected", "operation" -> operation.toString, "backend" -> scope.backend.value),
          ) *> process.available.flatMap(available =>
            metrics.gauge(MetricKeys.TransferBudgetAvailableBytes, available.toDouble, Map.empty)
          ) *> ZIO.logDebug(error.getMessage)
        }

    override def availableBytes: UIO[Long] = process.available

    private def validate(operation: StoreOperation, bytes: Long): IO[StoreError, Unit] =
      if bytes <= 0L then ZIO.fail(StoreError.InvalidInput(operation, "transfer reservation must be positive"))
      else if bytes > capacityBytes then ZIO.fail(StoreError.CapacityExceeded(operation, capacityBytes, Some(bytes)))
      else ZIO.unit

    private def toStoreError(operation: StoreOperation)(error: DistributedAdmission.Error): StoreError = error match
      case DistributedAdmission.Error.InvalidRequest(reason)     => StoreError.InvalidInput(operation, reason)
      case DistributedAdmission.Error.Rejected(dimension, retry) =>
        StoreError.DistributedAdmissionRejected(operation, dimension, retry)
      case DistributedAdmission.Error.TimedOut(timeout)          =>
        StoreError.DistributedAdmissionUnavailable(operation, s"timed out after $timeout")
      case DistributedAdmission.Error.Unavailable(reason)        => StoreError.DistributedAdmissionUnavailable(operation, reason)
      case _: DistributedAdmission.Error.LeaseLost               => StoreError.DistributedAdmissionLeaseLost(operation)
      case DistributedAdmission.Error.Protocol(reason)           => StoreError.DistributedAdmissionUnavailable(operation, reason)

  private final case class WeightedEntry(bytePermits: Semaphore, transferPermits: Semaphore, active: Int, sequence: Long)
  private final case class ConcurrentEntry(permits: Semaphore, active: Int, sequence: Long)

  private def tenantPermitsScoped(
    state: Ref.Synchronized[Map[TenantId, WeightedEntry]],
    tenantId: TenantId,
    operation: StoreOperation,
    bytes: Long,
    config: TransferAdmissionConfig,
  ): ZIO[Scope, StoreError, Unit] =
    if bytes > config.maximumTenantBufferedBytes.value then
      ZIO.fail(
        StoreError.TenantTransferCapacityExceeded(
          operation,
          tenantId,
          config.maximumTenantBufferedBytes.value,
          bytes,
        )
      )
    else
      ZIO
        .acquireRelease(acquireWeightedEntry(state, tenantId, operation, config))(entry => releaseWeightedEntry(state, tenantId, entry))
        .flatMap(entry => entry.bytePermits.withPermitsScoped(bytes) *> entry.transferPermits.withPermitScoped)

  private def backendPermitScoped(
    state: Ref.Synchronized[Map[StoreBackend, ConcurrentEntry]],
    backend: StoreBackend,
    operation: StoreOperation,
    config: TransferAdmissionConfig,
  ): ZIO[Scope, StoreError, Unit] =
    ZIO
      .acquireRelease(acquireConcurrentEntry(state, backend, operation, config))(entry => releaseConcurrentEntry(state, backend, entry))
      .flatMap(_.permits.withPermitScoped)

  private def acquireWeightedEntry(
    state: Ref.Synchronized[Map[TenantId, WeightedEntry]],
    tenantId: TenantId,
    operation: StoreOperation,
    config: TransferAdmissionConfig,
  ): IO[StoreError, WeightedEntry] =
    state.modifyZIO { entries =>
      entries.get(tenantId) match
        case Some(entry) =>
          val updated = entry.copy(
            active = java.lang.Math.addExact(entry.active, 1),
            sequence = nextSequence(entries.valuesIterator.map(_.sequence)),
          )
          ZIO.succeed(updated -> entries.updated(tenantId, updated))
        case None        =>
          makeRoom(entries, config.maximumResidentTenants, _.active, _.sequence) match
            case None            =>
              ZIO.fail(StoreError.TransferAdmissionSaturated(operation, "tenant", config.maximumResidentTenants))
            case Some(available) =>
              (Semaphore.make(config.maximumTenantBufferedBytes.value) zip
                Semaphore.make(config.maximumConcurrentTenantTransfers.value.toLong)).map { case (bytePermits, transferPermits) =>
                val created = WeightedEntry(bytePermits, transferPermits, 1, nextSequence(available.valuesIterator.map(_.sequence)))
                created -> available.updated(tenantId, created)
              }
    }

  private def releaseWeightedEntry(
    state: Ref.Synchronized[Map[TenantId, WeightedEntry]],
    tenantId: TenantId,
    acquired: WeightedEntry,
  ): UIO[Unit] =
    state.update { entries =>
      entries.get(tenantId) match
        case Some(current) if current.bytePermits eq acquired.bytePermits =>
          entries.updated(tenantId, current.copy(active = math.max(0, current.active - 1)))
        case _                                                            => entries
    }

  private def acquireConcurrentEntry(
    state: Ref.Synchronized[Map[StoreBackend, ConcurrentEntry]],
    backend: StoreBackend,
    operation: StoreOperation,
    config: TransferAdmissionConfig,
  ): IO[StoreError, ConcurrentEntry] =
    state.modifyZIO { entries =>
      entries.get(backend) match
        case Some(entry) =>
          val updated = entry.copy(
            active = java.lang.Math.addExact(entry.active, 1),
            sequence = nextSequence(entries.valuesIterator.map(_.sequence)),
          )
          ZIO.succeed(updated -> entries.updated(backend, updated))
        case None        =>
          makeRoom(entries, config.maximumResidentBackends, _.active, _.sequence) match
            case None            =>
              ZIO.fail(StoreError.TransferAdmissionSaturated(operation, "backend", config.maximumResidentBackends))
            case Some(available) =>
              Semaphore.make(config.maximumConcurrentBackendTransfers.value.toLong).map { permits =>
                val created = ConcurrentEntry(permits, 1, nextSequence(available.valuesIterator.map(_.sequence)))
                created -> available.updated(backend, created)
              }
    }

  private def releaseConcurrentEntry(
    state: Ref.Synchronized[Map[StoreBackend, ConcurrentEntry]],
    backend: StoreBackend,
    acquired: ConcurrentEntry,
  ): UIO[Unit] =
    state.update { entries =>
      entries.get(backend) match
        case Some(current) if current.permits eq acquired.permits =>
          entries.updated(backend, current.copy(active = math.max(0, current.active - 1)))
        case _                                                    => entries
    }

  private def makeRoom[K, V](
    entries: Map[K, V],
    maximum: Int,
    active: V => Int,
    sequence: V => Long,
  ): Option[Map[K, V]] =
    if entries.size < maximum then Some(entries)
    else
      entries.iterator
        .filter { case (_, value) => active(value) == 0 }
        .minByOption { case (_, value) => sequence(value) }
        .map { case (victim, _) => entries.removed(victim) }

  private def nextSequence(values: Iterator[Long]): Long =
    val current = values.foldLeft(0L)(math.max)
    if current == Long.MaxValue then 0L else current + 1L

  private def bound(underlying: TransferBudget, scope: TransferScope): TransferBudget = new TransferBudget:
    override def capacityBytes: Long                                                                                  = underlying.capacityBytes
    override def reserveScoped(bytes: Long): ZIO[Scope, StoreError, Unit]                                             =
      TransferFootprint
        .single(TransferComponent.applyUnsafe("legacy-byte-reservation"), bytes)
        .fold(
          error => ZIO.fail(StoreError.InvalidInput(StoreOperation.PutBlob, error.getMessage)),
          footprint => underlying.reserveScoped(scope, footprint),
        )
    override def reserveScoped(footprint: TransferFootprint): ZIO[Scope, StoreError, Unit]                            =
      underlying.reserveScoped(scope, footprint)
    override def reserveScoped(operation: StoreOperation, bytes: Long): ZIO[Scope, StoreError, Unit]                  =
      TransferFootprint
        .single(TransferComponent.applyUnsafe("legacy-byte-reservation"), bytes)
        .fold(
          error => ZIO.fail(StoreError.InvalidInput(operation, error.getMessage)),
          footprint => underlying.reserveScoped(scope, operation, footprint),
        )
    override def reserveScoped(operation: StoreOperation, footprint: TransferFootprint): ZIO[Scope, StoreError, Unit] =
      underlying.reserveScoped(scope, operation, footprint)
    override def reserveScoped(ignored: TransferScope, footprint: TransferFootprint): ZIO[Scope, StoreError, Unit]    =
      underlying.reserveScoped(scope, footprint)
    override def reserveScoped(
      ignored: TransferScope,
      operation: StoreOperation,
      footprint: TransferFootprint,
    ): ZIO[Scope, StoreError, Unit] = underlying.reserveScoped(scope, operation, footprint)
    override def bind(next: TransferScope): TransferBudget                                                            = bound(underlying, next)
    override def availableBytes: UIO[Long]                                                                            = underlying.availableBytes

  /** Compatibility only for direct constructors. Production layers use [[live]]. */
  val unbounded: TransferBudget = new TransferBudget:
    override val capacityBytes: Long                                                                 = Long.MaxValue
    override def reserveScoped(bytes: Long): ZIO[Scope, StoreError, Unit]                            =
      ZIO.fail(StoreError.InvalidInput(StoreOperation.PutBlob, "transfer reservation must be positive")).when(bytes <= 0L).unit
    override def reserveScoped(operation: StoreOperation, bytes: Long): ZIO[Scope, StoreError, Unit] =
      ZIO.fail(StoreError.InvalidInput(operation, "transfer reservation must be positive")).when(bytes <= 0L).unit
    override val availableBytes: UIO[Long]                                                           = ZIO.succeed(Long.MaxValue)

  val live: ZLayer[TransferMemoryConfig, Nothing, TransferBudget] =
    ZLayer.fromZIO(ZIO.serviceWithZIO[TransferMemoryConfig](make))

  val default: ZLayer[Any, Nothing, TransferBudget] =
    ZLayer.fromZIO(make(TransferMemoryConfig.Default))
