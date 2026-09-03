package graviton.backend.pg

import graviton.core.bytes.{Digest, HashAlgo}
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.runtime.lifecycle.ResourceFinalizer
import graviton.runtime.stores.*
import zio.*
import zio.stream.ZStream

import java.time.Instant
import javax.sql.DataSource

/** Shared repair progress and dead-letter journal for clustered repositories. */
final class PgRepairJournal(
  dataSource: DataSource,
  namespace: String = "replica-convergence",
) extends RepairJournal:

  override val loadCursor: IO[StoreError, Long] =
    blocking(StoreOperation.Repair) {
      val connection = dataSource.getConnection()
      try
        val statement = connection.prepareStatement(
          "SELECT next_offset FROM graviton.repair_state WHERE namespace = ?"
        )
        try
          statement.setString(1, namespace)
          val result = statement.executeQuery()
          try if result.next() then result.getLong(1) else 0L
          finally result.close()
        finally statement.close()
      finally connection.close()
    }

  override def checkpoint(nextOffset: Long): IO[StoreError, Unit] =
    if nextOffset < 0L then ZIO.fail(StoreError.InvalidInput(StoreOperation.Repair, "repair cursor must be non-negative"))
    else
      blocking(StoreOperation.Repair) {
        val connection = dataSource.getConnection()
        try
          val statement = connection.prepareStatement(
            """INSERT INTO graviton.repair_state(namespace, next_offset, updated_at)
              |VALUES (?, ?, clock_timestamp())
              |ON CONFLICT (namespace) DO UPDATE SET
              |  next_offset = EXCLUDED.next_offset,
              |  updated_at = EXCLUDED.updated_at""".stripMargin
          )
          try
            statement.setString(1, namespace)
            statement.setLong(2, nextOffset)
            statement.executeUpdate()
            ()
          finally statement.close()
        finally connection.close()
      }

  override def recordFailure(key: BinaryKey.Block, error: StoreError, failedAt: Instant): IO[StoreError, Unit] =
    for
      algorithm <- ZIO.fromEither(toDbAlgorithm(key.bits.algo)).mapError(StoreError.InvalidInput(StoreOperation.Repair, _))
      _         <- blocking(StoreOperation.Repair) {
                     val connection = dataSource.getConnection()
                     try
                       val statement = connection.prepareStatement(
                         """INSERT INTO graviton.repair_dead_letter(
                           |  namespace, alg, hash_bytes, byte_length, attempts, last_error, last_failed_at
                           |) VALUES (?, ?::core.hash_alg, ?, ?, 1, ?, ?)
                           |ON CONFLICT (namespace, alg, hash_bytes, byte_length) DO UPDATE SET
                           |  attempts = graviton.repair_dead_letter.attempts + 1,
                           |  last_error = EXCLUDED.last_error,
                           |  last_failed_at = EXCLUDED.last_failed_at""".stripMargin
                       )
                       try
                         statement.setString(1, namespace)
                         statement.setString(2, algorithm)
                         statement.setBytes(3, key.bits.digest.toInteropArray)
                         statement.setLong(4, key.bits.size)
                         statement.setString(5, RepairJournal.detail(error))
                         statement.setTimestamp(6, java.sql.Timestamp.from(failedAt))
                         statement.executeUpdate()
                         ()
                       finally statement.close()
                     finally connection.close()
                   }
    yield ()

  override def resolve(key: BinaryKey.Block): IO[StoreError, Unit] =
    for
      algorithm <- ZIO.fromEither(toDbAlgorithm(key.bits.algo)).mapError(StoreError.InvalidInput(StoreOperation.Repair, _))
      _         <- blocking(StoreOperation.Repair) {
                     val connection = dataSource.getConnection()
                     try
                       val statement = connection.prepareStatement(
                         """DELETE FROM graviton.repair_dead_letter
                           |WHERE namespace = ? AND alg = ?::core.hash_alg AND hash_bytes = ? AND byte_length = ?""".stripMargin
                       )
                       try
                         statement.setString(1, namespace)
                         statement.setString(2, algorithm)
                         statement.setBytes(3, key.bits.digest.toInteropArray)
                         statement.setLong(4, key.bits.size)
                         statement.executeUpdate()
                         ()
                       finally statement.close()
                     finally connection.close()
                   }
    yield ()

  override val deadLetters: ZStream[Any, StoreError, RepairDeadLetter] =
    ZStream.acquireReleaseWith(openDeadLetters)(closeCursor).flatMap { cursor =>
      ZStream.unfoldZIO(cursor) { current =>
        blocking(StoreOperation.Repair) {
          if !current.result.next() then None
          else Some(readDeadLetter(current.result) -> current)
        }
      }
    }

  override val healthCheck: IO[StoreError, Unit] = loadCursor.unit

  private def openDeadLetters: IO[StoreError, PgRepairJournal.Cursor] =
    blocking(StoreOperation.Repair) {
      val connection = dataSource.getConnection()
      try
        connection.setReadOnly(true)
        connection.setAutoCommit(false)
        val statement = connection.prepareStatement(
          """SELECT alg, hash_bytes, byte_length, attempts, last_error, last_failed_at
            |FROM graviton.repair_dead_letter
            |WHERE namespace = ?
            |ORDER BY alg::text, encode(hash_bytes, 'hex'), byte_length""".stripMargin
        )
        try
          statement.setFetchSize(256)
          statement.setString(1, namespace)
          PgRepairJournal.Cursor(connection, statement, statement.executeQuery())
        catch
          case error: Throwable =>
            statement.close()
            throw error
      catch
        case error: Throwable =>
          connection.close()
          throw error
    }

  private def closeCursor(cursor: PgRepairJournal.Cursor): UIO[Unit] =
    ResourceFinalizer.closeBlocking("PostgreSQL repair cursor") {
      try cursor.result.close()
      finally
        try cursor.statement.close()
        finally
          try cursor.connection.rollback()
          finally cursor.connection.close()
    }

  private def readDeadLetter(result: java.sql.ResultSet): RepairDeadLetter =
    val algorithmText = result.getString(1)
    val algorithm     = parseDbAlgorithm(algorithmText).getOrElse(
      throw new IllegalStateException(s"unsupported repair hash algorithm '$algorithmText'")
    )
    val digest        = Digest.fromArrayCopy(result.getBytes(2)).fold(message => throw new IllegalStateException(message), identity)
    val bits          = KeyBits.fromLong(algorithm, digest, result.getLong(3)).fold(message => throw new IllegalStateException(message), identity)
    val key           = BinaryKey.block(bits).fold(message => throw new IllegalStateException(message), identity)
    RepairDeadLetter(key, result.getLong(4), result.getString(5), result.getTimestamp(6).toInstant)

  private def blocking[A](operation: StoreOperation)(effect: => A): IO[StoreError, A] =
    ZIO.attemptBlocking(effect).mapError(PgStoreError.fromThrowable(operation, retryUnknown = true))

  private def toDbAlgorithm(algorithm: HashAlgo): Either[String, String] =
    algorithm match
      case HashAlgo.Sha256 => Right("sha256")
      case HashAlgo.Blake3 => Right("blake3")
      case other           => Left(s"unsupported repair hash algorithm '$other'")

  private def parseDbAlgorithm(value: String): Option[HashAlgo] =
    value.trim.toLowerCase match
      case "sha256" => Some(HashAlgo.Sha256)
      case "blake3" => Some(HashAlgo.Blake3)
      case _        => None

object PgRepairJournal:
  private final case class Cursor(
    connection: java.sql.Connection,
    statement: java.sql.PreparedStatement,
    result: java.sql.ResultSet,
  )

  val layer: ZLayer[DataSource, Nothing, RepairJournal] =
    ZLayer.fromFunction((dataSource: DataSource) => new PgRepairJournal(dataSource): RepairJournal)
