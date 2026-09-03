package graviton.backend.pg

import graviton.core.locator.BlobLocator
import graviton.runtime.stores.{MutableObjectStore, StoreError, StoreOperation}
import zio.*
import zio.stream.*

import java.sql.Connection
import javax.sql.DataSource

final class PgMutableObjectStore(dataSource: DataSource) extends PgImmutableObjectStore(dataSource), MutableObjectStore:

  override def put(locator: BlobLocator): ZSink[Any, StoreError, Byte, Nothing, Unit] =
    ZSink.unwrapScoped {
      (for
        connection <- transaction
        _          <- deleteWithin(connection, locator)
        _          <- insertObject(connection, locator)
      yield ZSink
        .foldLeftChunksZIO[Any, Throwable, Byte, PgObjectWriteState](PgObjectWriteState.empty) { (state, chunk) =>
          writeChunk(connection, locator, state, chunk)
        }
        .mapZIO { state =>
          updateSize(connection, locator, state.totalBytes) *>
            ZIO.attemptBlocking(connection.commit()).unit
        }
        .ignoreLeftover
        .mapError(storeError(StoreOperation.PutObject))).mapError(storeError(StoreOperation.PutObject))
    }

  override def delete(locator: BlobLocator): IO[StoreError, Unit] =
    ZIO
      .scoped(transaction.flatMap(connection => deleteWithin(connection, locator) *> ZIO.attemptBlocking(connection.commit()).unit))
      .mapError(storeError(StoreOperation.DeleteObject))

  override def copy(src: BlobLocator, dest: BlobLocator): IO[StoreError, Unit] =
    ZIO
      .scoped {
        transaction.flatMap { connection =>
          for
            _        <- deleteWithin(connection, dest)
            inserted <- ZIO.attemptBlocking {
                          val statement = connection.prepareStatement(
                            """INSERT INTO graviton.object_data (locator, scheme, bucket, path, byte_length)
                              |SELECT ?, ?, ?, ?, byte_length
                              |FROM graviton.object_data
                              |WHERE locator = ?""".stripMargin
                          )
                          try
                            statement.setString(1, dest.render)
                            statement.setString(2, dest.scheme.value)
                            statement.setString(3, dest.bucket.value)
                            statement.setString(4, dest.path.value)
                            statement.setString(5, src.render)
                            statement.executeUpdate()
                          finally statement.close()
                        }
            _        <- ZIO
                          .fail(new NoSuchElementException(s"Source object '${src.render}' does not exist"))
                          .when(inserted != 1)
            _        <- ZIO.attemptBlocking {
                          val statement = connection.prepareStatement(
                            """INSERT INTO graviton.object_chunk (locator, ordinal, bytes)
                              |SELECT ?, ordinal, bytes
                              |FROM graviton.object_chunk
                              |WHERE locator = ?
                              |ORDER BY ordinal""".stripMargin
                          )
                          try
                            statement.setString(1, dest.render)
                            statement.setString(2, src.render)
                            statement.executeUpdate()
                            ()
                          finally statement.close()
                        }
            _        <- ZIO.attemptBlocking(connection.commit()).unit
          yield ()
        }
      }
      .mapError(storeError(StoreOperation.CopyObject))

  private def transaction: ZIO[Scope, Throwable, Connection] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking {
        val connection = dataSource.getConnection()
        connection.setAutoCommit(false)
        connection
      }
    )(connection =>
      ZIO.attemptBlocking(connection.rollback()).ignore *>
        graviton.runtime.lifecycle.ResourceFinalizer.closeBlocking("PostgreSQL mutable-object connection")(connection.close())
    )

  private def deleteWithin(connection: Connection, locator: BlobLocator): Task[Unit] =
    ZIO.attemptBlocking {
      val statement = connection.prepareStatement("DELETE FROM graviton.object_data WHERE locator = ?")
      try
        statement.setString(1, locator.render)
        statement.executeUpdate()
        ()
      finally statement.close()
    }

  private def insertObject(connection: Connection, locator: BlobLocator): Task[Unit] =
    ZIO.attemptBlocking {
      val statement = connection.prepareStatement(
        """INSERT INTO graviton.object_data (locator, scheme, bucket, path, byte_length)
          |VALUES (?, ?, ?, ?, 0)""".stripMargin
      )
      try
        statement.setString(1, locator.render)
        statement.setString(2, locator.scheme.value)
        statement.setString(3, locator.bucket.value)
        statement.setString(4, locator.path.value)
        statement.executeUpdate()
        ()
      finally statement.close()
    }

  private def writeChunk(
    connection: Connection,
    locator: BlobLocator,
    initial: PgObjectWriteState,
    input: Chunk[Byte],
  ): Task[PgObjectWriteState] =
    def loop(state: PgObjectWriteState, remaining: Chunk[Byte]): Task[PgObjectWriteState] =
      if remaining.isEmpty then ZIO.succeed(state)
      else
        val part = remaining.take(PgMutableObjectStore.MaxChunkBytes)
        for
          _      <- ZIO.attemptBlocking {
                      val statement = connection.prepareStatement(
                        "INSERT INTO graviton.object_chunk (locator, ordinal, bytes) VALUES (?, ?, ?)"
                      )
                      try
                        statement.setString(1, locator.render)
                        statement.setLong(2, state.nextOrdinal)
                        statement.setBytes(3, part.toArray)
                        statement.executeUpdate()
                        ()
                      finally statement.close()
                    }
          next   <- ZIO
                      .attempt(Math.addExact(state.totalBytes, part.length.toLong))
                      .mapError(_ => new IllegalArgumentException("Object byte length overflow"))
          updated = state.copy(nextOrdinal = state.nextOrdinal + 1L, totalBytes = next)
          result <- loop(updated, remaining.drop(part.length))
        yield result

    loop(initial, input)

  private def updateSize(connection: Connection, locator: BlobLocator, size: Long): Task[Unit] =
    ZIO.attemptBlocking {
      val statement = connection.prepareStatement(
        "UPDATE graviton.object_data SET byte_length = ?, updated_at = clock_timestamp() WHERE locator = ?"
      )
      try
        statement.setLong(1, size)
        statement.setString(2, locator.render)
        if statement.executeUpdate() != 1 then throw new IllegalStateException(s"Object '${locator.render}' disappeared during upload")
      finally statement.close()
    }

private object PgMutableObjectStore:
  val MaxChunkBytes: Int = 1024 * 1024

private final case class PgObjectWriteState(nextOrdinal: Long, totalBytes: Long)
private object PgObjectWriteState:
  val empty: PgObjectWriteState = PgObjectWriteState(0L, 0L)
