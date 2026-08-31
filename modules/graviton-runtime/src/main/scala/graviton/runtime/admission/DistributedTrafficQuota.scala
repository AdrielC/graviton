package graviton.runtime.admission

import graviton.runtime.upload.TenantId
import zio.*

/** Atomic, cluster-wide contractual usage limits, separate from memory admission. */
trait DistributedTrafficQuota:
  def charge(tenantId: TenantId, kind: DistributedTrafficQuota.Kind, amount: Long): IO[DistributedTrafficQuota.Error, Unit]

object DistributedTrafficQuota:
  enum Kind:
    case Request, DeliveredEgress

  sealed abstract class Error(message: String) extends Exception(message)
  object Error:
    final case class InvalidCharge(reason: String) extends Error(reason)
    final case class Rejected(kind: Kind, limit: Long, retryAfter: Duration)
        extends Error(s"$kind quota exceeded at $limit; retry after $retryAfter")
    final case class Unavailable(reason: String)   extends Error(s"distributed traffic quota is unavailable: $reason")
    final case class Protocol(reason: String)      extends Error(s"distributed traffic quota protocol error: $reason")

  val disabled: DistributedTrafficQuota = new DistributedTrafficQuota:
    override def charge(tenantId: TenantId, kind: Kind, amount: Long): IO[Error, Unit] =
      val _ = (tenantId, kind)
      if amount > 0L then ZIO.unit else ZIO.fail(Error.InvalidCharge("traffic quota charge must be positive"))

  val disabledLayer: ULayer[DistributedTrafficQuota] = ZLayer.succeed(disabled)
