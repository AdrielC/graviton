package quasar.legacy.service

import zio.*

import java.util.UUID

/**
 * Tenant-implicit context (resolved from deployment + auth in real wiring).
 *
 * For now this is an explicit service dependency so we do not leak org/principal ids into URLs.
 */
trait TenantContext:
  def orgId: UIO[UUID]
  def principalId: UIO[UUID]

object TenantContext:
  final case class Static(org: UUID, principal: UUID) extends TenantContext:
    override def orgId: UIO[UUID]       = ZIO.succeed(org)
    override def principalId: UIO[UUID] = ZIO.succeed(principal)

  def static(org: UUID, principal: UUID): ULayer[TenantContext] =
    ZLayer.succeed(Static(org, principal))
