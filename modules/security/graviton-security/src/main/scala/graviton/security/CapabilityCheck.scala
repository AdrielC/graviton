package graviton.security

import zio.*

import java.util.UUID
import javax.sql.DataSource

/**
 * Resolves the effective capabilities of a caller against a specific
 * resource by folding the caller's JWT-scoped capabilities together with
 * any `graviton.acl_entry` rows for the same `(org_id, principal_id,
 * resource_kind, resource_id)`.
 *
 * Deny entries always win: a single `effect='deny'` row clears the bit
 * regardless of allow grants.
 */
trait CapabilityCheck:
  /** Fails with [[SecurityError.Forbidden]] if the caller lacks `required`. */
  def require(required: Capability, resource: ResourceRef): IO[SecurityError, Unit]

  /** Returns the full effective capability mask for the resource (for logging / UI). */
  def effective(resource: ResourceRef): IO[SecurityError, CapabilitySet]

object CapabilityCheck:

  /**
   * Pure variant that only checks JWT-scoped capabilities. Suitable for
   * unit tests and for deployments that encode all authorization in the
   * token itself.
   */
  val tokenOnly: CapabilityCheck = new CapabilityCheck:
    def require(required: Capability, resource: ResourceRef): IO[SecurityError, Unit] =
      CallerContext.required.flatMap { ctx =>
        if ctx.has(required) then ZIO.unit
        else ZIO.fail(SecurityError.Forbidden(s"missing capability $required for ${resource.kind}", Some(required)))
      }

    def effective(resource: ResourceRef): IO[SecurityError, CapabilitySet] =
      CallerContext.required.map(_.capabilities)

  /**
   * Layer that consults `graviton.acl_entry` on top of the JWT-scoped caps.
   *
   * Uses a short, parameterised SQL query; relies on Postgres RLS to scope
   * rows to the caller's org (the calling code is responsible for having
   * set `app.org_id` on the connection before calling).
   */
  def jdbc: URLayer[DataSource, CapabilityCheck] =
    ZLayer.fromFunction((ds: DataSource) => new JdbcCapabilityCheck(ds))

  private final class JdbcCapabilityCheck(ds: DataSource) extends CapabilityCheck:

    def require(required: Capability, resource: ResourceRef): IO[SecurityError, Unit] =
      effective(resource).flatMap { caps =>
        if caps.contains(required) then ZIO.unit
        else ZIO.fail(SecurityError.Forbidden(s"missing capability $required for ${resource.kind}", Some(required)))
      }

    def effective(resource: ResourceRef): IO[SecurityError, CapabilitySet] =
      CallerContext.required.flatMap { ctx =>
        resource.id match
          case None        => ZIO.succeed(ctx.capabilities)
          case Some(resId) =>
            TenantScopedBlocking
              .attemptBlocking(queryAclMask(ctx.orgId, ctx.principalId, resource.kind.dbValue, resId))
              .mapBoth(
                err => SecurityError.MisconfiguredSecurity(s"ACL lookup failed: ${err.getMessage}", Some(err)),
                acl =>
                  val (allow, deny) = acl
                  CapabilitySet.fromMask((ctx.capabilities.mask | allow) & ~deny),
              )
      }

    private def queryAclMask(orgId: UUID, principalId: UUID, kind: String, resourceId: UUID): (Long, Long) =
      val sql  = """
        SELECT effect::text, capabilities
        FROM graviton.acl_entry
        WHERE org_id = ?
          AND principal_id = ?
          AND resource_kind = ?::graviton.resource_kind
          AND resource_id = ?
      """
      val conn = ds.getConnection
      conn.setAutoCommit(false)
      try
        val context = conn.prepareStatement(
          "SELECT set_config('app.org_id', ?, true), set_config('app.principal_id', ?, true)"
        )
        try
          context.setString(1, orgId.toString)
          context.setString(2, principalId.toString)
          val result = context.executeQuery()
          try
            val _ = result.next()
          finally result.close()
        finally context.close()

        val stmt  = conn.prepareStatement(sql)
        val masks =
          try
            stmt.setObject(1, orgId)
            stmt.setObject(2, principalId)
            stmt.setString(3, kind)
            stmt.setObject(4, resourceId)
            val rs = stmt.executeQuery()
            try
              var allow = 0L
              var deny  = 0L
              while rs.next() do
                val effect = rs.getString(1)
                val caps   = rs.getLong(2)
                if effect == "allow" then allow |= caps
                else if effect == "deny" then deny |= caps
              (allow, deny)
            finally rs.close()
          finally stmt.close()
        conn.commit()
        masks
      catch
        case error: Throwable =>
          try conn.rollback()
          catch case _: Throwable => ()
          throw error
      finally
        try conn.setAutoCommit(true)
        catch case _: Throwable => ()
        conn.close()
