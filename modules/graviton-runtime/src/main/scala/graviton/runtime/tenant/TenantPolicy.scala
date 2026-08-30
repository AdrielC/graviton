package graviton.runtime.tenant

import graviton.core.RefinedTypeExt
import graviton.core.types.FileSize
import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import graviton.runtime.model.{BlobDescription, BlobListing, BlobStat, InventoryCursor, InventoryPage, InventoryPageSize}
import graviton.runtime.stores.*
import graviton.runtime.upload.TenantId
import io.github.iltotore.iron.constraint.numeric
import io.github.iltotore.iron.constraint.collection.MaxLength
import zio.*
import zio.stream.{ZSink, ZStream}

/** Per-process concurrency ceiling for one tenant. */
type TenantConcurrencyLimit = TenantConcurrencyLimit.T
object TenantConcurrencyLimit extends RefinedTypeExt[Int, numeric.GreaterEqual[1] & numeric.LessEqual[65535]]

/** Monotonic durable policy version used to invalidate cached store bindings. */
type TenantPolicyRevision = TenantPolicyRevision.T
object TenantPolicyRevision extends RefinedTypeExt[Long, numeric.GreaterEqual[0L]]

/** Durable retained logical bytes allowed for one tenant. */
type TenantRetainedBytesLimit = TenantRetainedBytesLimit.T
object TenantRetainedBytesLimit extends RefinedTypeExt[Long, numeric.GreaterEqual[1L]]

/** Operator-owned deployment cell. Separate cells use separate credentials and storage. */
type TenantCellId = TenantCellId.T
object TenantCellId extends RefinedTypeExt[String, graviton.core.types.IdentifierConstraint & MaxLength[120]]:
  val Default: TenantCellId = applyUnsafe("default")

enum TenantLifecycle:
  case Active, Suspended

/**
 * Server-owned storage and admission policy for one authenticated organization.
 * A caller can never select its deduplication domain or limits.
 */
final case class TenantPolicy(
  route: TenantRoute,
  lifecycle: TenantLifecycle,
  maxConcurrentOperations: TenantConcurrencyLimit,
  maxObjectBytes: FileSize,
  maxRetainedBytes: TenantRetainedBytesLimit,
  revision: TenantPolicyRevision,
)

/** Durable source of tenant policy. Implementations must fail closed. */
trait TenantPolicyCatalog:
  def resolve(tenantId: TenantId): IO[TenantRoutingError, TenantPolicy]

object TenantPolicyCatalog:
  def fromFunction(resolveTenant: TenantId => IO[TenantRoutingError, TenantPolicy]): TenantPolicyCatalog =
    new TenantPolicyCatalog:
      override def resolve(tenantId: TenantId): IO[TenantRoutingError, TenantPolicy] = resolveTenant(tenantId)

  def static(policies: Chunk[TenantPolicy]): Either[TenantTopologyError, TenantPolicyCatalog] =
    val duplicate = policies.groupBy(_.route.tenantId).collectFirst { case (tenantId, values) if values.lengthCompare(1) > 0 => tenantId }
    duplicate match
      case Some(tenantId) => Left(TenantTopologyError.DuplicateTenant(tenantId))
      case None           =>
        val byTenant = policies.map(policy => policy.route.tenantId -> policy).toMap
        Right(
          fromFunction(tenantId =>
            ZIO
              .fromOption(byTenant.get(tenantId))
              .orElseFail(TenantRoutingError.UnknownTenant(tenantId))
              .flatMap(requireActive)
          )
        )

  def cached(
    underlying: TenantPolicyCatalog,
    maximumEntries: Int,
    timeToLive: Duration,
  ): UIO[TenantPolicyCatalog] =
    for
      _      <- ZIO.dieMessage("tenant policy cache maximumEntries must be positive").unless(maximumEntries > 0)
      _      <- ZIO.dieMessage("tenant policy cache timeToLive must be positive").unless(timeToLive.toNanos > 0L)
      shards <- ZIO.foreach(0 until shardCount(maximumEntries)) { index =>
                  Ref.Synchronized
                    .make(CacheState.empty)
                    .map(ref => CacheShard(ref, shardCapacity(maximumEntries, shardCount(maximumEntries), index)))
                }
    yield new TenantPolicyCatalog:
      override def resolve(tenantId: TenantId): IO[TenantRoutingError, TenantPolicy] =
        for
          now    <- Clock.nanoTime
          signal <- Promise.make[TenantRoutingError, TenantPolicy]
          shard   = shards(shardIndex(tenantId, shards.length))
          action <- shard.state.modify(_.decide(tenantId, signal, now, shard.capacity))
          policy <- action match
                      case CacheDecision.Hit(value)   => ZIO.succeed(value)
                      case CacheDecision.Await(ready) => ready.await
                      case CacheDecision.Load(ready)  =>
                        completeLoad(underlying, shard.state, tenantId, ready, timeToLive)
                      case CacheDecision.Bypass       => underlying.resolve(tenantId).flatMap(requireActive)
        yield policy

  private def requireActive(policy: TenantPolicy): IO[TenantRoutingError, TenantPolicy] =
    policy.lifecycle match
      case TenantLifecycle.Active    => ZIO.succeed(policy)
      case TenantLifecycle.Suspended => ZIO.fail(TenantRoutingError.SuspendedTenant(policy.route.tenantId))

  private enum CacheDecision:
    case Hit(policy: TenantPolicy)
    case Await(signal: Promise[TenantRoutingError, TenantPolicy])
    case Load(signal: Promise[TenantRoutingError, TenantPolicy])
    case Bypass

  private sealed trait CacheEntry:
    def lastAccessNanos: Long

  private final case class CachedPolicy(policy: TenantPolicy, expiresAtNanos: Long, lastAccessNanos: Long) extends CacheEntry
  private final case class LoadingPolicy(
    signal: Promise[TenantRoutingError, TenantPolicy],
    lastAccessNanos: Long,
  ) extends CacheEntry

  private final case class CacheState(entries: Map[TenantId, CacheEntry]):
    def decide(
      tenantId: TenantId,
      candidate: Promise[TenantRoutingError, TenantPolicy],
      now: Long,
      capacity: Int,
    ): (CacheDecision, CacheState) =
      entries.get(tenantId) match
        case Some(value: CachedPolicy) if now < value.expiresAtNanos =>
          CacheDecision.Hit(value.policy) -> copy(
            entries = entries.updated(tenantId, value.copy(lastAccessNanos = now))
          )
        case Some(value: LoadingPolicy)                              =>
          CacheDecision.Await(value.signal) -> copy(
            entries = entries.updated(tenantId, value.copy(lastAccessNanos = now))
          )
        case _                                                       =>
          val withoutExpired = entries.removed(tenantId)
          val withRoom       =
            if withoutExpired.size < capacity then Some(withoutExpired)
            else
              withoutExpired.iterator
                .collect { case (key, value: CachedPolicy) => key -> value }
                .minByOption(_._2.lastAccessNanos)
                .map { case (victim, _) => withoutExpired.removed(victim) }
          withRoom match
            case Some(available) =>
              CacheDecision.Load(candidate) -> copy(entries = available.updated(tenantId, LoadingPolicy(candidate, now)))
            case None            => CacheDecision.Bypass -> this

    def complete(
      tenantId: TenantId,
      signal: Promise[TenantRoutingError, TenantPolicy],
      result: Exit[TenantRoutingError, TenantPolicy],
      completedAt: Long,
      timeToLive: Duration,
    ): CacheState =
      entries.get(tenantId) match
        case Some(loading: LoadingPolicy) if loading.signal eq signal =>
          result match
            case Exit.Success(policy) =>
              copy(entries = entries.updated(tenantId, CachedPolicy(policy, saturatedAdd(completedAt, timeToLive.toNanos), completedAt)))
            case Exit.Failure(_)      => copy(entries = entries.removed(tenantId))
        case _                                                        => this

  private object CacheState:
    val empty: CacheState = CacheState(Map.empty)

  private final case class CacheShard(state: Ref.Synchronized[CacheState], capacity: Int)

  private def completeLoad(
    underlying: TenantPolicyCatalog,
    state: Ref.Synchronized[CacheState],
    tenantId: TenantId,
    signal: Promise[TenantRoutingError, TenantPolicy],
    timeToLive: Duration,
  ): IO[TenantRoutingError, TenantPolicy] =
    ZIO.uninterruptibleMask { restore =>
      restore(underlying.resolve(tenantId).flatMap(requireActive)).exit.flatMap { result =>
        for
          completedAt <- Clock.nanoTime
          _           <- state.update(_.complete(tenantId, signal, result, completedAt, timeToLive))
          _           <- signal.done(result)
          policy      <- result match
                           case Exit.Success(value) => ZIO.succeed(value)
                           case Exit.Failure(cause) => ZIO.refailCause(cause)
        yield policy
      }
    }

  private def shardCount(maximumEntries: Int): Int = math.min(64, maximumEntries)

  private def shardCapacity(maximumEntries: Int, shards: Int, index: Int): Int =
    val base      = maximumEntries / shards
    val remainder = maximumEntries % shards
    base + (if index < remainder then 1 else 0)

  private def shardIndex(tenantId: TenantId, shards: Int): Int =
    java.lang.Math.floorMod(tenantId.value.hashCode, shards)

  private def saturatedAdd(left: Long, right: Long): Long =
    try java.lang.Math.addExact(left, right)
    catch case _: ArithmeticException => Long.MaxValue

/**
 * Bounded, cancellation-safe per-process admission. Entries with active or
 * waiting operations are never evicted, so one tenant cannot reset its limit
 * by churning the policy cache.
 */
trait TenantAdmission:
  def acquireScoped(policy: TenantPolicy): ZIO[Scope, TenantAdmission.Error, Unit]
  def residentTenants: UIO[Int]

object TenantAdmission:
  sealed abstract class Error(message: String) extends Exception(message)
  object Error:
    final case class Saturated(maximumResidentTenants: Int)
        extends Error(s"tenant admission registry is saturated at $maximumResidentTenants resident tenants")
    final case class TimedOut(tenantId: TenantId, timeout: Duration)
        extends Error(s"tenant ${tenantId.value} did not obtain an operation permit within $timeout")
    final case class PolicyChanging(tenantId: TenantId)
        extends Error(s"tenant ${tenantId.value} admission policy is changing while operations are active")

  def make(
    maximumResidentTenants: Int,
    acquisitionTimeout: Duration,
    metrics: MetricsRegistry = MetricsRegistry.noop,
  ): UIO[TenantAdmission] =
    for
      _      <- ZIO.dieMessage("tenant admission maximumResidentTenants must be positive").unless(maximumResidentTenants > 0)
      _      <- ZIO.dieMessage("tenant admission acquisitionTimeout must be positive").unless(acquisitionTimeout.toNanos > 0L)
      shards <- ZIO.foreach(0 until admissionShardCount(maximumResidentTenants)) { index =>
                  Ref.Synchronized
                    .make(AdmissionState.empty)
                    .map(state => AdmissionShard(state, admissionShardCapacity(maximumResidentTenants, index)))
                }
    yield new TenantAdmission:
      override def acquireScoped(policy: TenantPolicy): ZIO[Scope, Error, Unit] =
        val shard = shards(admissionShardIndex(policy.route.tenantId, shards.length))
        ZIO
          .acquireRelease(acquireEntry(shard.state, policy, shard.capacity, maximumResidentTenants))(entry =>
            releaseEntry(shard.state, policy.route.tenantId, entry)
          )
          .flatMap { entry =>
            entry.permits.withPermitScoped
              .timeoutFail(Error.TimedOut(policy.route.tenantId, acquisitionTimeout))(acquisitionTimeout)
          }
          .tapBoth(
            {
              case _: Error.Saturated      => metrics.counter(MetricKeys.TenantAdmissionTotal, Map("outcome" -> "registry_saturated"))
              case _: Error.TimedOut       => metrics.counter(MetricKeys.TenantAdmissionTotal, Map("outcome" -> "timed_out"))
              case _: Error.PolicyChanging => metrics.counter(MetricKeys.TenantAdmissionTotal, Map("outcome" -> "policy_changing"))
            },
            _ => metrics.counter(MetricKeys.TenantAdmissionTotal, Map("outcome" -> "admitted")),
          )

      override def residentTenants: UIO[Int] = ZIO.foreach(shards)(_.state.get.map(_.entries.size)).map(_.sum)

  private final case class AdmissionEntry(
    permits: Semaphore,
    limit: TenantConcurrencyLimit,
    revision: TenantPolicyRevision,
    active: Int,
    lastAccess: Long,
  )

  private final case class AdmissionState(entries: Map[TenantId, AdmissionEntry], sequence: Long):
    def nextSequence: Long = if sequence == Long.MaxValue then 0L else sequence + 1L

  private object AdmissionState:
    val empty: AdmissionState = AdmissionState(Map.empty, 0L)

  private final case class AdmissionShard(state: Ref.Synchronized[AdmissionState], capacity: Int)

  private def acquireEntry(
    state: Ref.Synchronized[AdmissionState],
    policy: TenantPolicy,
    shardCapacity: Int,
    reportedMaximumResidentTenants: Int,
  ): IO[Error, AdmissionEntry] =
    state.modifyZIO { current =>
      val tenantId = policy.route.tenantId
      current.entries.get(tenantId) match
        case Some(entry) if entry.limit == policy.maxConcurrentOperations =>
          val updated = entry.copy(active = java.lang.Math.addExact(entry.active, 1), lastAccess = current.nextSequence)
          ZIO.succeed(updated -> current.copy(entries = current.entries.updated(tenantId, updated), sequence = current.nextSequence))
        case Some(entry) if entry.active > 0                              =>
          ZIO.fail(Error.PolicyChanging(tenantId))
        case _                                                            =>
          val withoutTenant = current.entries.removed(tenantId)
          val room          =
            if withoutTenant.size < shardCapacity then Some(withoutTenant)
            else
              withoutTenant.iterator
                .filter(_._2.active == 0)
                .minByOption(_._2.lastAccess)
                .map { case (victim, _) => withoutTenant.removed(victim) }
          room match
            case None          => ZIO.fail(Error.Saturated(reportedMaximumResidentTenants))
            case Some(entries) =>
              Semaphore.make(policy.maxConcurrentOperations.value.toLong).map { permits =>
                val created = AdmissionEntry(
                  permits,
                  policy.maxConcurrentOperations,
                  policy.revision,
                  active = 1,
                  lastAccess = current.nextSequence,
                )
                created -> current.copy(entries = entries.updated(tenantId, created), sequence = current.nextSequence)
              }
    }

  private def releaseEntry(
    state: Ref.Synchronized[AdmissionState],
    tenantId: TenantId,
    acquired: AdmissionEntry,
  ): UIO[Unit] =
    state.update { current =>
      current.entries.get(tenantId) match
        case Some(entry) if entry.permits eq acquired.permits =>
          current.copy(entries = current.entries.updated(tenantId, entry.copy(active = math.max(0, entry.active - 1))))
        case _                                                => current
    }

  private def admissionShardCount(maximumEntries: Int): Int = math.min(64, maximumEntries)

  private def admissionShardCapacity(maximumEntries: Int, index: Int): Int =
    val shards    = admissionShardCount(maximumEntries)
    val base      = maximumEntries / shards
    val remainder = maximumEntries % shards
    base + (if index < remainder then 1 else 0)

  private def admissionShardIndex(tenantId: TenantId, shards: Int): Int =
    java.lang.Math.floorMod(tenantId.value.hashCode, shards)

/** Adds per-tenant admission and a streamed object-size ceiling to one resolved store. */
final class AdmittedTenantBlobStore(
  underlying: BlobStore,
  policy: TenantPolicy,
  admission: TenantAdmission,
) extends BlobStore:

  override def put(plan: graviton.runtime.model.BlobWritePlan = graviton.runtime.model.BlobWritePlan()): BlobSink =
    ZSink.unwrapScoped[Any] {
      for
        _        <- admission.acquireScoped(policy).mapError(toStoreError(StoreOperation.PutBlob))
        consumed <- Ref.make(0L)
      yield underlying.put(plan).contramapChunksZIO { bytes =>
        consumed
          .modify { current =>
            val next =
              try Some(java.lang.Math.addExact(current, bytes.length.toLong))
              catch case _: ArithmeticException => None
            next match
              case Some(value) => value         -> value
              case None        => Long.MaxValue -> Long.MaxValue
          }
          .flatMap { total =>
            if total <= policy.maxObjectBytes.value then ZIO.succeed(bytes)
            else
              ZIO.fail(
                StoreError.CapacityExceeded(
                  StoreOperation.PutBlob,
                  policy.maxObjectBytes.value,
                  Some(total),
                )
              )
          }
      }
    }

  override def get(key: graviton.core.keys.BinaryKey.Blob): ZStream[Any, StoreError, Byte] =
    admittedStream(StoreOperation.GetBlob)(underlying.get(key))

  override def getRange(
    key: graviton.core.keys.BinaryKey.Blob,
    start: graviton.core.types.BlobOffset,
    length: FileSize,
  ): ZStream[Any, StoreError, Byte] =
    admittedStream(StoreOperation.GetRange)(underlying.getRange(key, start, length))

  override def stat(key: graviton.core.keys.BinaryKey.Blob): IO[StoreError, Option[BlobStat]] =
    admittedEffect(StoreOperation.StatBlob)(underlying.stat(key))

  override def inventoryPage(after: Option[InventoryCursor], limit: InventoryPageSize): IO[StoreError, InventoryPage[BlobListing]] =
    admittedEffect(StoreOperation.Inventory)(underlying.inventoryPage(after, limit))

  override def inspect(key: graviton.core.keys.BinaryKey.Blob): IO[StoreError, Option[BlobDescription]] =
    admittedEffect(StoreOperation.InspectBlob)(underlying.inspect(key))

  override def delete(key: graviton.core.keys.BinaryKey.Blob): IO[StoreError, Unit] =
    admittedEffect(StoreOperation.DeleteBlob)(underlying.delete(key))

  override def healthCheck: IO[StoreError, Unit] =
    admittedEffect(StoreOperation.HealthCheck)(underlying.healthCheck)

  private def admittedEffect[A](operation: StoreOperation)(effect: IO[StoreError, A]): IO[StoreError, A] =
    ZIO.scoped(admission.acquireScoped(policy).mapError(toStoreError(operation)) *> effect)

  private def admittedStream(operation: StoreOperation)(stream: ZStream[Any, StoreError, Byte]): ZStream[Any, StoreError, Byte] =
    ZStream.unwrapScoped[Any](admission.acquireScoped(policy).mapError(toStoreError(operation)).as(stream))

  private def toStoreError(operation: StoreOperation)(error: TenantAdmission.Error): StoreError = error match
    case _: TenantAdmission.Error.Saturated      => StoreError.TenantAdmissionUnavailable(operation)
    case _: TenantAdmission.Error.TimedOut       => StoreError.TenantConcurrencyExceeded(operation)
    case _: TenantAdmission.Error.PolicyChanging => StoreError.TenantAdmissionUnavailable(operation)
