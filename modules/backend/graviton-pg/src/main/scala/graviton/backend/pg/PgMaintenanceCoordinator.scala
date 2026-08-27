package graviton.backend.pg

import graviton.runtime.config.MaintenanceConfig
import graviton.runtime.stores.{LocalMaintenanceGate, MaintenanceCoordinator, MaintenanceError, MaintenanceMode}
import zio.*

import java.sql.Connection
import javax.sql.DataSource

/**
 * PostgreSQL session-advisory-lock coordinator for independent Graviton
 * processes sharing one manifest database and block repository.
 *
 * Ordinary operations use the shared form of one namespaced lock. Maintenance
 * uses the exclusive form. Polling `pg_try_advisory_lock*` keeps acquisition
 * responsive to ZIO interruption and the configured timeout.
 */
final class PgMaintenanceCoordinator private (
  dataSource: DataSource,
  config: MaintenanceConfig,
  local: LocalMaintenanceGate,
  shared: Ref[Option[PgMaintenanceCoordinator.SharedLock]],
  guard: Semaphore,
) extends MaintenanceCoordinator:

  override val operationPermit: ZIO[Scope, MaintenanceError, Unit] =
    localPermit(MaintenanceMode.Operation) *>
      MaintenanceCoordinator.acquireScopedInterruptibly(acquireShared)(_ => releaseShared)

  override val maintenanceLease: ZIO[Scope, MaintenanceError, Unit] =
    localPermit(MaintenanceMode.Maintenance) *>
      MaintenanceCoordinator.acquireScopedInterruptibly(acquire(MaintenanceMode.Maintenance))(release).unit

  override val healthCheck: IO[MaintenanceError, Unit] =
    ZIO.acquireReleaseWith(openConnection)(closeConnection) { connection =>
      ZIO
        .attemptBlocking {
          val statement = connection.prepareStatement("SELECT hashtextextended(?, 0)")
          try
            statement.setString(1, lockDomain)
            val result = statement.executeQuery()
            try
              if !result.next() then throw new IllegalStateException("PostgreSQL lock hash probe returned no row")
            finally result.close()
          finally statement.close()
        }
        .mapError(error => backendFailure("health check", error))
    }

  private val lockDomain: String = s"graviton:repository:${config.namespace.value}"

  private def localPermit(mode: MaintenanceMode): ZIO[Scope, MaintenanceError, Unit] =
    mode match
      case MaintenanceMode.Operation   =>
        MaintenanceCoordinator
          .acquireScopedInterruptibly(
            local.acquireOperation.timeoutFail(
              MaintenanceError.AcquisitionTimedOut(config.namespace, mode, config.acquisitionTimeout)
            )(config.acquisitionTimeout)
          )(_ => local.releaseOperation)
          .unit
      case MaintenanceMode.Maintenance =>
        MaintenanceCoordinator
          .acquireScopedInterruptibly(
            local.acquireMaintenance.timeoutFail(
              MaintenanceError.AcquisitionTimedOut(config.namespace, mode, config.acquisitionTimeout)
            )(config.acquisitionTimeout)
          )(_ => local.releaseMaintenance)
          .unit

  private def acquireShared: IO[MaintenanceError, Unit] =
    guard.withPermit {
      shared.get.flatMap {
        case Some(current) =>
          shared.set(Some(current.copy(users = java.lang.Math.addExact(current.users, 1))))
        case None          =>
          acquire(MaintenanceMode.Operation)
            .flatMap(held => shared.set(Some(PgMaintenanceCoordinator.SharedLock(held, users = 1))))
      }
    }

  private def releaseShared: UIO[Unit] =
    guard.withPermit {
      shared.get.flatMap {
        case Some(current) if current.users > 1 =>
          shared.set(Some(current.copy(users = current.users - 1)))
        case Some(current)                      =>
          release(current.held) *> shared.set(None)
        case None                               =>
          ZIO.logError(s"PostgreSQL coordination refcount underflow for '$lockDomain'")
      }
    }

  private def acquire(mode: MaintenanceMode): IO[MaintenanceError, PgMaintenanceCoordinator.HeldLock] =
    ZIO.uninterruptibleMask { restore =>
      openConnection.flatMap { connection =>
        restore(waitForLock(connection, mode)).exit.flatMap {
          case Exit.Success(_)     => ZIO.succeed(PgMaintenanceCoordinator.HeldLock(connection, mode))
          case Exit.Failure(cause) =>
            closeConnection(connection) *> ZIO.refailCause(cause)
        }
      }
    }

  private def waitForLock(connection: Connection, mode: MaintenanceMode): IO[MaintenanceError, Unit] =
    def loop: IO[MaintenanceError, Unit] =
      tryAcquire(connection, mode).flatMap {
        case true  => ZIO.unit
        case false => ZIO.sleep(config.pollInterval) *> ZIO.suspendSucceed(loop)
      }

    loop.timeoutFail(MaintenanceError.AcquisitionTimedOut(config.namespace, mode, config.acquisitionTimeout))(
      config.acquisitionTimeout
    )

  private def tryAcquire(connection: Connection, mode: MaintenanceMode): IO[MaintenanceError, Boolean] =
    val function = mode match
      case MaintenanceMode.Operation   => "pg_try_advisory_lock_shared"
      case MaintenanceMode.Maintenance => "pg_try_advisory_lock"

    ZIO
      .attemptBlocking {
        val statement = connection.prepareStatement(s"SELECT $function(hashtextextended(?, 0))")
        try
          statement.setString(1, lockDomain)
          val result = statement.executeQuery()
          try
            if !result.next() then throw new IllegalStateException(s"$function returned no row")
            result.getBoolean(1)
          finally result.close()
        finally statement.close()
      }
      .mapError(error => backendFailure(s"try ${mode.label} advisory lock", error))

  private def release(held: PgMaintenanceCoordinator.HeldLock): UIO[Unit] =
    val function = held.mode match
      case MaintenanceMode.Operation   => "pg_advisory_unlock_shared"
      case MaintenanceMode.Maintenance => "pg_advisory_unlock"

    ZIO
      .attemptBlocking {
        val statement = held.connection.prepareStatement(s"SELECT $function(hashtextextended(?, 0))")
        try
          statement.setString(1, lockDomain)
          val result = statement.executeQuery()
          try
            if !result.next() || !result.getBoolean(1) then
              throw new IllegalStateException(s"$function reported that this session did not own the lock")
          finally result.close()
        finally statement.close()
      }
      .foldZIO(
        error => ZIO.logError(s"Failed to release PostgreSQL coordination for '$lockDomain': ${error.getMessage}"),
        _ => ZIO.unit,
      )
      .ensuring(closeConnection(held.connection))

  private def openConnection: IO[MaintenanceError, Connection] =
    ZIO
      .attemptBlocking(dataSource.getConnection())
      .mapError(error => backendFailure("open PostgreSQL coordination connection", error))

  private def closeConnection(connection: Connection): UIO[Unit] =
    ZIO
      .attemptBlocking(connection.close())
      .catchAll(error => ZIO.logError(s"Failed to close PostgreSQL coordination connection: ${error.getMessage}"))

  private def backendFailure(operation: String, error: Throwable): MaintenanceError =
    MaintenanceError.BackendFailure(config.namespace, operation, error)

object PgMaintenanceCoordinator:
  private final case class HeldLock(connection: Connection, mode: MaintenanceMode)
  private final case class SharedLock(held: HeldLock, users: Int)

  def make(
    dataSource: DataSource,
    config: MaintenanceConfig = MaintenanceConfig.Default,
  ): IO[IllegalArgumentException, MaintenanceCoordinator] =
    for
      valid  <- ZIO.fromEither(config.validate).mapError(new IllegalArgumentException(_))
      local  <- LocalMaintenanceGate.make
      shared <- Ref.make(Option.empty[SharedLock])
      guard  <- Semaphore.make(1L)
    yield new PgMaintenanceCoordinator(dataSource, valid, local, shared, guard)

  def layer(
    config: MaintenanceConfig
  ): ZLayer[DataSource, IllegalArgumentException, MaintenanceCoordinator] =
    ZLayer.fromZIO {
      for
        ds          <- ZIO.service[DataSource]
        coordinator <- make(ds, config)
      yield coordinator
    }

  val configured: ZLayer[DataSource & MaintenanceConfig, IllegalArgumentException, MaintenanceCoordinator] =
    ZLayer.fromZIO {
      for
        ds          <- ZIO.service[DataSource]
        config      <- ZIO.service[MaintenanceConfig]
        coordinator <- make(ds, config)
      yield coordinator
    }
