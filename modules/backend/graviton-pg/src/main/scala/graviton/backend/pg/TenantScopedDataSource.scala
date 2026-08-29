package graviton.backend.pg

import graviton.security.{CallerContext, TenantScopedBlocking}
import zio.*

import java.sql.Connection
import javax.sql.DataSource

/**
 * A [[DataSource]] wrapper that stamps every checked-out connection with
 * the ambient [[CallerContext]] by running
 * {{{ SELECT set_config('app.org_id', <uuid>, true),
 *            set_config('app.principal_id', <uuid>, true) }}}
 * so that the Postgres RLS policies can enforce tenant isolation via
 * `graviton.current_org_id()`.
 *
 * The ambient context is supplied via a ThreadLocal set by
 * [[TenantScopedBlocking.attemptBlocking]] — the helper that JDBC-heavy
 * call sites must use in place of `ZIO.attemptBlocking`. ThreadLocal is
 * the cheapest bridge for synchronous JDBC code because the context only
 * has to live for the duration of a single blocking call (no fiber hops).
 *
 * If no context is set, the GUC is left untouched and `current_org_id()`
 * returns `NULL`; every tenant-scoped RLS policy then sees zero rows, so
 * the default is deny.
 *
 * Production application roles must use `NOBYPASSRLS` so a buggy migration
 * cannot leak cross-tenant data.
 */
final class TenantScopedDataSource(underlying: DataSource) extends DataSource:

  override def getConnection: Connection =
    val conn = underlying.getConnection
    try TenantScopedDataSource.applyContextIfPresent(conn)
    catch
      case err: Throwable =>
        try conn.close()
        catch case _: Throwable => ()
        throw err
    conn

  override def getConnection(username: String, password: String): Connection =
    val conn = underlying.getConnection(username, password)
    try TenantScopedDataSource.applyContextIfPresent(conn)
    catch
      case err: Throwable =>
        try conn.close()
        catch case _: Throwable => ()
        throw err
    conn

  override def getLogWriter: java.io.PrintWriter            = underlying.getLogWriter
  override def setLogWriter(out: java.io.PrintWriter): Unit = underlying.setLogWriter(out)
  override def setLoginTimeout(seconds: Int): Unit          = underlying.setLoginTimeout(seconds)
  override def getLoginTimeout: Int                         = underlying.getLoginTimeout
  override def getParentLogger: java.util.logging.Logger    = underlying.getParentLogger
  override def unwrap[T](iface: Class[T]): T                = underlying.unwrap(iface)
  override def isWrapperFor(iface: Class[?]): Boolean       = underlying.isWrapperFor(iface)

object TenantScopedDataSource:

  /** Wrap an existing [[DataSource]] so every checked-out connection is tenant-scoped. */
  def wrap(ds: DataSource): DataSource = new TenantScopedDataSource(ds)

  /** Layer variant: takes an input DataSource and returns the tenant-scoped one. */
  val layer: ZLayer[DataSource, Nothing, DataSource] =
    ZLayer.fromFunction((ds: DataSource) => wrap(ds))

  private[pg] def applyContextIfPresent(conn: Connection): Unit =
    Option(TenantScopedBlocking.currentContext.get).foreach(applyContext(conn, _))

  private[pg] def applyContext(conn: Connection, ctx: CallerContext): Unit =
    val stmt = conn.prepareStatement(
      "SELECT set_config('app.org_id', ?, true), set_config('app.principal_id', ?, true)"
    )
    try
      stmt.setString(1, ctx.orgId.toString)
      stmt.setString(2, ctx.principalId.toString)
      val rs = stmt.executeQuery()
      try ()
      finally rs.close()
    finally stmt.close()
