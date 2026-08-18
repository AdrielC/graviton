package graviton.security

import zio.*

/**
 * Bridges the fiber-local [[CallerContext]] into synchronous JDBC call
 * sites. Use this in place of `ZIO.attemptBlocking` whenever the blocking
 * body needs to check out a connection from a tenant-scoped `DataSource`:
 *
 * {{{
 *   TenantScopedBlocking.attemptBlocking {
 *     val conn = ds.getConnection()   // GUCs applied by TenantScopedDataSource
 *     try runQueries(conn) finally conn.close()
 *   }
 * }}}
 *
 * The helper works by copying the current fiber's [[CallerContext]] into
 * a ThreadLocal for the lifetime of the blocking body. ThreadLocals don't
 * survive fiber hops, but the body itself runs on a single blocking
 * thread, so this is enough.
 *
 * When no CallerContext is set on the fiber, the body still runs but the
 * ThreadLocal stays empty; downstream tenant-scoped RLS policies will see
 * `NULL` for `current_org_id()` and return no rows, which is the safe
 * deny-by-default behaviour.
 */
object TenantScopedBlocking:

  val currentContext: ThreadLocal[CallerContext] = new ThreadLocal[CallerContext]()

  def attemptBlocking[A](body: => A): ZIO[Any, Throwable, A] =
    CallerContext.current.flatMap {
      case Some(ctx) =>
        ZIO.attemptBlocking {
          currentContext.set(ctx)
          try body
          finally currentContext.remove()
        }
      case None      =>
        ZIO.attemptBlocking(body)
    }

  def attemptBlockingWith[A](ctx: CallerContext)(body: => A): ZIO[Any, Throwable, A] =
    ZIO.attemptBlocking {
      currentContext.set(ctx)
      try body
      finally currentContext.remove()
    }

  /**
   * Run a synchronous body with the given context already in the
   * ThreadLocal. Used by tests and by explicit request-scoped callers
   * that don't want to go through the fiber-ref.
   */
  def runSync[A](ctx: CallerContext)(body: => A): A =
    currentContext.set(ctx)
    try body
    finally currentContext.remove()
