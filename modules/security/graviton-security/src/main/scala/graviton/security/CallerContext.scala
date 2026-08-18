package graviton.security

import zio.*

import java.time.Instant
import java.util.UUID

/**
 * The authenticated identity of a request. Flows through HTTP, gRPC, and DB
 * layers via a [[FiberRef]] so handlers never have to thread it manually.
 *
 * Lifetime: bound to a single request/call. Not shared across fibers beyond
 * that call's scope.
 */
final case class CallerContext(
  orgId: UUID,
  principalId: UUID,
  capabilities: CapabilitySet,
  jti: String,
  tokenExpiresAt: Instant,
  requestId: UUID,
  sourceIp: Option[String] = None,
  userAgent: Option[String] = None,
):
  def isExpired(now: Instant): Boolean = !now.isBefore(tokenExpiresAt)

  def has(cap: Capability): Boolean = capabilities.contains(cap)

object CallerContext:

  val currentRef: FiberRef[Option[CallerContext]] =
    Unsafe.unsafe(implicit u => FiberRef.unsafe.make[Option[CallerContext]](None))

  val current: UIO[Option[CallerContext]]        = currentRef.get
  val required: IO[SecurityError, CallerContext] =
    currentRef.get.flatMap {
      case Some(ctx) => ZIO.succeed(ctx)
      case None      => ZIO.fail(SecurityError.Unauthenticated("no caller context on fiber"))
    }

  def scopedWith[R, E, A](ctx: CallerContext)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    currentRef.locally(Some(ctx))(zio)
