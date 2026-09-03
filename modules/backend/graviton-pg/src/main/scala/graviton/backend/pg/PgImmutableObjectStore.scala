package graviton.backend.pg

import graviton.core.locator.BlobLocator
import graviton.runtime.stores.{ImmutableObjectStore, StoreError, StoreOperation}
import zio.stream.ZStream
import zio.{Chunk, IO, Task, UIO, ZIO}

import java.sql.{Connection, PreparedStatement, ResultSet}
import javax.sql.DataSource

class PgImmutableObjectStore protected[pg] (protected val dataSource: DataSource) extends ImmutableObjectStore:

  override def head(locator: BlobLocator): IO[StoreError, Option[Long]] =
    ZIO
      .attemptBlocking {
        val connection = dataSource.getConnection()
        try
          val statement = connection.prepareStatement("SELECT byte_length FROM graviton.object_data WHERE locator = ?")
          try
            statement.setString(1, locator.render)
            val result = statement.executeQuery()
            try if result.next() then Some(result.getLong(1)) else None
            finally result.close()
          finally statement.close()
        finally connection.close()
      }
      .mapError(storeError(StoreOperation.HeadObject))

  override def list(prefix: String): ZStream[Any, StoreError, BlobLocator] =
    ZStream
      .acquireReleaseWith(openLocatorCursor(prefix))(closeCursor)
      .flatMap { cursor =>
        ZStream.unfoldChunkZIO(cursor) { current =>
          ZIO.attemptBlocking {
            val values = Chunk.newBuilder[BlobLocator]
            var count  = 0
            while count < PgImmutableObjectStore.FetchSize && current.result.next() do
              val locator = BlobLocator
                .from(current.result.getString(1), current.result.getString(2), current.result.getString(3))
                .fold(message => throw new IllegalStateException(message), identity)
              values += locator
              count += 1
            val chunk  = values.result()
            if chunk.isEmpty then None else Some((chunk, current))
          }
        }
      }
      .mapError(storeError(StoreOperation.ListObjects))

  override def get(locator: BlobLocator): ZStream[Any, StoreError, Byte] =
    ZStream
      .acquireReleaseWith(openChunkCursor(locator))(closeCursor)
      .flatMap { cursor =>
        ZStream.unfoldChunkZIO(cursor) { current =>
          ZIO.attemptBlocking {
            if current.result.next() then Some((Chunk.fromArray(current.result.getBytes(1)), current))
            else None
          }
        }
      }
      .mapError(storeError(StoreOperation.GetObject))

  protected final def storeError(operation: StoreOperation)(error: Throwable): StoreError =
    PgStoreError.fromThrowable(operation, retryUnknown = true)(error)

  private def openLocatorCursor(prefix: String): Task[PgObjectCursor] =
    openCursor(
      """SELECT scheme, bucket, path
        |FROM graviton.object_data
        |WHERE starts_with(locator, ?)
        |ORDER BY locator""".stripMargin,
      statement => statement.setString(1, prefix),
    )

  private def openChunkCursor(locator: BlobLocator): Task[PgObjectCursor] =
    openCursor(
      """SELECT bytes
        |FROM graviton.object_chunk
        |WHERE locator = ?
        |ORDER BY ordinal""".stripMargin,
      statement => statement.setString(1, locator.render),
    )

  private def openCursor(sql: String, bind: PreparedStatement => Unit): Task[PgObjectCursor] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection()
      try
        connection.setAutoCommit(false)
        val statement = connection.prepareStatement(sql)
        try
          statement.setFetchSize(PgImmutableObjectStore.FetchSize)
          bind(statement)
          PgObjectCursor(connection, statement, statement.executeQuery())
        catch
          case error: Throwable =>
            statement.close()
            throw error
      catch
        case error: Throwable =>
          connection.close()
          throw error
    }

  private def closeCursor(cursor: PgObjectCursor): UIO[Unit] =
    ZIO.attemptBlocking {
      try cursor.result.close()
      finally
        try cursor.statement.close()
        finally cursor.connection.close()
    }.orDie

private object PgImmutableObjectStore:
  val FetchSize: Int = 256

private final case class PgObjectCursor(
  connection: Connection,
  statement: PreparedStatement,
  result: ResultSet,
)
