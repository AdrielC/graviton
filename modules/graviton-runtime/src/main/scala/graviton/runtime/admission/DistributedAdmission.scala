package graviton.runtime.admission

import graviton.core.RefinedTypeExt
import graviton.runtime.stores.{StoreOperation, TransferFootprint, TransferScope}
import graviton.runtime.upload.TenantId
import io.github.iltotore.iron.constraint.collection.{MaxLength, MinLength}
import io.github.iltotore.iron.constraint.numeric.{GreaterEqual, LessEqual}
import zio.*

/** Opaque identifier for one renewable cluster admission lease. */
type AdmissionLeaseId = AdmissionLeaseId.T
object AdmissionLeaseId extends RefinedTypeExt[String, MinLength[1] & MaxLength[128]]

/** Monotonic token issued by the coordinator for stale-owner detection. */
type AdmissionFencingToken = AdmissionFencingToken.T
object AdmissionFencingToken extends RefinedTypeExt[Long, GreaterEqual[1L]]

/** Version of the dynamic policy snapshot used by an acquisition. */
type AdmissionPolicyVersion = AdmissionPolicyVersion.T
object AdmissionPolicyVersion extends RefinedTypeExt[Long, GreaterEqual[0L]]:
  val Initial: AdmissionPolicyVersion = applyUnsafe(0L)

/** Positive byte ceiling used by the cluster coordinator and live policies. */
type DistributedBufferedBytes = DistributedBufferedBytes.T
object DistributedBufferedBytes extends RefinedTypeExt[Long, GreaterEqual[1L]]

/** Positive, operationally bounded transfer concurrency. */
type DistributedTransferConcurrency = DistributedTransferConcurrency.T
object DistributedTransferConcurrency extends RefinedTypeExt[Int, GreaterEqual[1] & LessEqual[65535]]

/** Cluster-wide limits. Tenant-specific overrides are provider-owned data. */
final case class DistributedAdmissionLimits(
  maximumServiceBufferedBytes: DistributedBufferedBytes,
  maximumConcurrentServiceTransfers: DistributedTransferConcurrency,
  maximumTenantBufferedBytes: DistributedBufferedBytes,
  maximumConcurrentTenantTransfers: DistributedTransferConcurrency,
  maximumConcurrentBackendTransfers: DistributedTransferConcurrency,
):
  def validate: Either[String, DistributedAdmissionLimits] =
    for
      _ <- Either.cond(
             maximumTenantBufferedBytes.value <= maximumServiceBufferedBytes.value,
             (),
             "tenant bytes must not exceed service bytes",
           )
      _ <- Either.cond(
             maximumConcurrentTenantTransfers.value <= maximumConcurrentServiceTransfers.value,
             (),
             "tenant concurrency must not exceed service concurrency",
           )
      _ <- Either.cond(
             maximumConcurrentBackendTransfers.value <= maximumConcurrentServiceTransfers.value,
             (),
             "backend concurrency must not exceed service concurrency",
           )
    yield this

object DistributedAdmissionLimits:
  val Default: DistributedAdmissionLimits = DistributedAdmissionLimits(
    maximumServiceBufferedBytes = DistributedBufferedBytes.applyUnsafe(4L * 1024L * 1024L * 1024L),
    maximumConcurrentServiceTransfers = DistributedTransferConcurrency.applyUnsafe(256),
    maximumTenantBufferedBytes = DistributedBufferedBytes.applyUnsafe(512L * 1024L * 1024L),
    maximumConcurrentTenantTransfers = DistributedTransferConcurrency.applyUnsafe(32),
    maximumConcurrentBackendTransfers = DistributedTransferConcurrency.applyUnsafe(192),
  )

/** One atomic request across the service, tenant, and physical backend axes. */
final case class DistributedAdmissionRequest(
  scope: TransferScope,
  operation: StoreOperation,
  footprint: TransferFootprint,
)

enum AdmissionDimension:
  case ServiceBytes
  case ServiceTransfers
  case TenantBytes
  case TenantTransfers
  case BackendTransfers

/** Bounded-cardinality occupancy returned by the admission coordinator. */
final case class AdmissionOccupancy(
  serviceBufferedBytes: Long,
  serviceTransfers: Long,
  tenantBufferedBytes: Option[Long],
  tenantTransfers: Option[Long],
  backendTransfers: Long,
)

final case class AdmissionSnapshot(
  scope: TransferScope,
  occupancy: AdmissionOccupancy,
  policyVersion: AdmissionPolicyVersion,
  observedAtEpochMillis: Long,
)

/**
 * A scoped renewable lease. `revoked` never succeeds: it remains pending while
 * the lease is healthy and fails when the provider can no longer prove
 * ownership. The local transfer budget remains the hard memory boundary even
 * if the distributed coordinator is partitioned.
 */
trait DistributedAdmissionLease:
  def id: AdmissionLeaseId
  def fencingToken: AdmissionFencingToken
  def policyVersion: AdmissionPolicyVersion
  def occupancyAtAdmission: AdmissionOccupancy
  def revoked: IO[DistributedAdmission.Error, Nothing]

/** Provider-neutral, cluster-wide hierarchical transfer admission. */
trait DistributedAdmission:
  def acquireScoped(request: DistributedAdmissionRequest): ZIO[Scope, DistributedAdmission.Error, DistributedAdmissionLease]
  def snapshot(scope: TransferScope): IO[DistributedAdmission.Error, AdmissionSnapshot]

object DistributedAdmission:
  sealed abstract class Error(message: String) extends Exception(message)
  object Error:
    final case class InvalidRequest(reason: String)  extends Error(reason)
    final case class Rejected(dimension: AdmissionDimension, retryAfter: Duration)
        extends Error(s"distributed admission rejected by $dimension; retry after $retryAfter")
    final case class TimedOut(timeout: Duration)     extends Error(s"distributed admission timed out after $timeout")
    final case class Unavailable(reason: String)     extends Error(s"distributed admission is unavailable: $reason")
    final case class LeaseLost(id: AdmissionLeaseId) extends Error(s"distributed admission lease ${id.value} was lost")
    final case class Protocol(reason: String)        extends Error(s"distributed admission protocol error: $reason")

  def acquireScoped(
    request: DistributedAdmissionRequest
  ): ZIO[DistributedAdmission & Scope, Error, DistributedAdmissionLease] =
    ZIO.serviceWithZIO[DistributedAdmission](_.acquireScoped(request))

  def snapshot(scope: TransferScope): ZIO[DistributedAdmission, Error, AdmissionSnapshot] =
    ZIO.serviceWithZIO[DistributedAdmission](_.snapshot(scope))

  /** Explicit no-op for single-process and embedded deployments. */
  val disabled: DistributedAdmission = new DistributedAdmission:
    override def acquireScoped(request: DistributedAdmissionRequest): ZIO[Scope, Error, DistributedAdmissionLease] =
      val _ = request
      ZIO.succeed(DisabledLease)

    override def snapshot(scope: TransferScope): IO[Error, AdmissionSnapshot] =
      Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS).map { now =>
        AdmissionSnapshot(
          scope,
          AdmissionOccupancy(0L, 0L, scope.tenantId.map(_ => 0L), scope.tenantId.map(_ => 0L), 0L),
          AdmissionPolicyVersion.Initial,
          now,
        )
      }

  val disabledLayer: ULayer[DistributedAdmission] = ZLayer.succeed(disabled)

  private object DisabledLease extends DistributedAdmissionLease:
    override val id: AdmissionLeaseId                             = AdmissionLeaseId.applyUnsafe("disabled")
    override val fencingToken: AdmissionFencingToken              = AdmissionFencingToken.applyUnsafe(1L)
    override val policyVersion: AdmissionPolicyVersion            = AdmissionPolicyVersion.Initial
    override val occupancyAtAdmission: AdmissionOccupancy         = AdmissionOccupancy(0L, 0L, None, None, 0L)
    override val revoked: IO[DistributedAdmission.Error, Nothing] = ZIO.never

/** Dynamic control-plane override. `None` restores configured defaults. */
final case class TenantAdmissionOverride(
  maximumBufferedBytes: Option[DistributedBufferedBytes] = None,
  maximumConcurrentTransfers: Option[DistributedTransferConcurrency] = None,
):
  def validate(defaults: DistributedAdmissionLimits): Either[String, TenantAdmissionOverride] =
    for
      _ <- maximumBufferedBytes.fold[Either[String, Unit]](Right(()))(value =>
             Either.cond(value.value <= defaults.maximumServiceBufferedBytes.value, (), "tenant override bytes are out of range")
           )
      _ <- maximumConcurrentTransfers.fold[Either[String, Unit]](Right(()))(value =>
             Either.cond(
               value.value <= defaults.maximumConcurrentServiceTransfers.value,
               (),
               "tenant override concurrency is out of range",
             )
           )
    yield this

/** Optional administrative port for predictive or scheduled policy controllers. */
trait DistributedAdmissionControl:
  def setTenantOverride(tenantId: TenantId, value: TenantAdmissionOverride): IO[DistributedAdmission.Error, AdmissionPolicyVersion]
  def clearTenantOverride(tenantId: TenantId): IO[DistributedAdmission.Error, AdmissionPolicyVersion]

object DistributedAdmissionControl:
  def setTenantOverride(
    tenantId: TenantId,
    value: TenantAdmissionOverride,
  ): ZIO[DistributedAdmissionControl, DistributedAdmission.Error, AdmissionPolicyVersion] =
    ZIO.serviceWithZIO[DistributedAdmissionControl](_.setTenantOverride(tenantId, value))

  def clearTenantOverride(
    tenantId: TenantId
  ): ZIO[DistributedAdmissionControl, DistributedAdmission.Error, AdmissionPolicyVersion] =
    ZIO.serviceWithZIO[DistributedAdmissionControl](_.clearTenantOverride(tenantId))
