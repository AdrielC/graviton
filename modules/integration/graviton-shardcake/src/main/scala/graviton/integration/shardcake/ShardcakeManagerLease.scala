package graviton.integration.shardcake

import zio.*

import java.sql.Connection
import javax.sql.DataSource

/**
 * Process-lifetime PostgreSQL lease for the single logical Shardcake manager.
 *
 * Shardcake deliberately has one manager. Holding a session advisory lock
 * makes that topology fail closed across deployments and rolling restarts.
 */
sealed trait ShardcakeManagerLease

object ShardcakeManagerLease:
  sealed trait Error extends Exception

  object Error:
    final case class AlreadyHeld() extends Exception("another Graviton Shardcake manager already holds the PostgreSQL lease") with Error

    final case class Database(operation: String, cause: Throwable)
        extends Exception(s"Shardcake manager lease $operation failed: ${cause.getMessage}", cause)
        with Error

  private final case class Live(connection: Connection) extends ShardcakeManagerLease

  private val AdvisoryLockId = 7094116700425191202L

  val live: ZLayer[DataSource, Error, ShardcakeManagerLease] =
    ZLayer.scoped {
      for
        dataSource <- ZIO.service[DataSource]
        connection <- ZIO.acquireRelease(acquire(dataSource))(release)
        _          <- ZIO.logInfo("Acquired the PostgreSQL Shardcake manager lease")
      yield Live(connection)
    }

  private def acquire(dataSource: DataSource): IO[Error, Connection] =
    ZIO
      .attemptBlocking(dataSource.getConnection)
      .mapError(Error.Database("connection", _))
      .flatMap { connection =>
        tryLock(connection)
          .onError(_ => close(connection))
          .flatMap {
            case true  => ZIO.succeed(connection)
            case false => close(connection) *> ZIO.fail(Error.AlreadyHeld())
          }
      }

  private def tryLock(connection: Connection): IO[Error, Boolean] =
    ZIO
      .attemptBlocking {
        val statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")
        try
          statement.setLong(1, AdvisoryLockId)
          val result = statement.executeQuery()
          try result.next() && result.getBoolean(1)
          finally result.close()
        finally statement.close()
      }
      .mapError(Error.Database("acquisition", _))

  private def release(connection: Connection): UIO[Unit] =
    ZIO
      .attemptBlocking {
        val statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")
        try
          statement.setLong(1, AdvisoryLockId)
          val result = statement.executeQuery()
          try
            val _ = result.next()
            ()
          finally result.close()
        finally statement.close()
      }
      .tapError(error => ZIO.logErrorCause("Could not release the PostgreSQL Shardcake manager lease", Cause.fail(error)))
      .ignore *> close(connection)

  private def close(connection: Connection): UIO[Unit] =
    ZIO
      .attemptBlocking(connection.close())
      .tapError(error => ZIO.logErrorCause("Could not close the Shardcake manager lease connection", Cause.fail(error)))
      .ignore
