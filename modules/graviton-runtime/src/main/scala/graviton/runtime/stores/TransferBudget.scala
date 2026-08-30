package graviton.runtime.stores

import graviton.runtime.config.{TransferAdmissionConfig, TransferMemoryConfig}
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
  def reserveScoped(footprint: TransferFootprint): ZIO[Scope, StoreError, Unit]                       =
    reserveScoped(footprint.totalBytes)
  def reserveScoped(scope: TransferScope, footprint: TransferFootprint): ZIO[Scope, StoreError, Unit] =
    val _ = scope
    reserveScoped(footprint)
  def bind(scope: TransferScope): TransferBudget                                                      = TransferBudget.bound(this, scope)
  def availableBytes: UIO[Long]

object TransferBudget:
  val service: ZIO[TransferBudget, Nothing, TransferBudget] = ZIO.service[TransferBudget]

  def make(config: TransferMemoryConfig): UIO[TransferBudget] =
    make(config, TransferAdmissionConfig.Default)

  def make(config: TransferMemoryConfig, admission: TransferAdmissionConfig): UIO[TransferBudget] =
    ZIO.dieMessage("invalid transfer admission configuration").unless(admission.validate.isRight) *>
      (for
        process  <- Semaphore.make(config.maximumBufferedBytes.value)
        tenants  <- Ref.Synchronized.make(Map.empty[TenantId, WeightedEntry])
        backends <- Ref.Synchronized.make(Map.empty[StoreBackend, ConcurrentEntry])
      yield new Live(config.maximumBufferedBytes.value, process, tenants, backends, admission))

  private final class Live(
    override val capacityBytes: Long,
    process: Semaphore,
    tenants: Ref.Synchronized[Map[TenantId, WeightedEntry]],
    backends: Ref.Synchronized[Map[StoreBackend, ConcurrentEntry]],
    admission: TransferAdmissionConfig,
  ) extends TransferBudget:

    override def reserveScoped(bytes: Long): ZIO[Scope, StoreError, Unit] =
      validate(bytes) *> process.withPermitsScoped(bytes)

    override def reserveScoped(scope: TransferScope, footprint: TransferFootprint): ZIO[Scope, StoreError, Unit] =
      val bytes   = footprint.totalBytes
      val acquire =
        validate(bytes) *>
          process.withPermitsScoped(bytes) *>
          ZIO.foreachDiscard(scope.tenantId)(tenant => tenantPermitsScoped(tenants, tenant, bytes, admission)) *>
          backendPermitScoped(backends, scope.backend, admission)

      acquire.timeoutFail(
        StoreError.TransferAdmissionTimedOut(
          StoreOperation.PutBlob,
          scope.backend,
          scope.tenantId,
          bytes,
          admission.acquisitionTimeout,
        )
      )(admission.acquisitionTimeout)

    override def availableBytes: UIO[Long] = process.available

    private def validate(bytes: Long): IO[StoreError, Unit] =
      if bytes <= 0L then ZIO.fail(StoreError.InvalidInput(StoreOperation.PutBlob, "transfer reservation must be positive"))
      else if bytes > capacityBytes then ZIO.fail(StoreError.CapacityExceeded(StoreOperation.PutBlob, capacityBytes, Some(bytes)))
      else ZIO.unit

  private final case class WeightedEntry(bytePermits: Semaphore, transferPermits: Semaphore, active: Int, sequence: Long)
  private final case class ConcurrentEntry(permits: Semaphore, active: Int, sequence: Long)

  private def tenantPermitsScoped(
    state: Ref.Synchronized[Map[TenantId, WeightedEntry]],
    tenantId: TenantId,
    bytes: Long,
    config: TransferAdmissionConfig,
  ): ZIO[Scope, StoreError, Unit] =
    if bytes > config.maximumTenantBufferedBytes.value then
      ZIO.fail(
        StoreError.TenantTransferCapacityExceeded(
          StoreOperation.PutBlob,
          tenantId,
          config.maximumTenantBufferedBytes.value,
          bytes,
        )
      )
    else
      ZIO
        .acquireRelease(acquireWeightedEntry(state, tenantId, config))(entry => releaseWeightedEntry(state, tenantId, entry))
        .flatMap(entry => entry.bytePermits.withPermitsScoped(bytes) *> entry.transferPermits.withPermitScoped)

  private def backendPermitScoped(
    state: Ref.Synchronized[Map[StoreBackend, ConcurrentEntry]],
    backend: StoreBackend,
    config: TransferAdmissionConfig,
  ): ZIO[Scope, StoreError, Unit] =
    ZIO
      .acquireRelease(acquireConcurrentEntry(state, backend, config))(entry => releaseConcurrentEntry(state, backend, entry))
      .flatMap(_.permits.withPermitScoped)

  private def acquireWeightedEntry(
    state: Ref.Synchronized[Map[TenantId, WeightedEntry]],
    tenantId: TenantId,
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
              ZIO.fail(StoreError.TransferAdmissionSaturated(StoreOperation.PutBlob, "tenant", config.maximumResidentTenants))
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
              ZIO.fail(StoreError.TransferAdmissionSaturated(StoreOperation.PutBlob, "backend", config.maximumResidentBackends))
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
    override def capacityBytes: Long                                                                               = underlying.capacityBytes
    override def reserveScoped(bytes: Long): ZIO[Scope, StoreError, Unit]                                          =
      TransferFootprint
        .single(TransferComponent.applyUnsafe("legacy-byte-reservation"), bytes)
        .fold(
          error => ZIO.fail(StoreError.InvalidInput(StoreOperation.PutBlob, error.getMessage)),
          footprint => underlying.reserveScoped(scope, footprint),
        )
    override def reserveScoped(footprint: TransferFootprint): ZIO[Scope, StoreError, Unit]                         =
      underlying.reserveScoped(scope, footprint)
    override def reserveScoped(ignored: TransferScope, footprint: TransferFootprint): ZIO[Scope, StoreError, Unit] =
      underlying.reserveScoped(scope, footprint)
    override def bind(next: TransferScope): TransferBudget                                                         = bound(underlying, next)
    override def availableBytes: UIO[Long]                                                                         = underlying.availableBytes

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
