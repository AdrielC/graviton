package graviton.backend.pg

import graviton.runtime.kv.{KeyValueStore, KvKey, KvValue}
import zio.{ZIO, ZLayer}

import javax.sql.DataSource

final class PgKeyValueStore(private val dataSource: DataSource) extends KeyValueStore:

  override def put(key: KvKey, value: KvValue): ZIO[Any, Throwable, Unit] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection()
      try
        val statement = connection.prepareStatement(
          """INSERT INTO graviton.key_value (key, value)
            |VALUES (?, ?)
            |ON CONFLICT (key) DO UPDATE
            |SET value = EXCLUDED.value, updated_at = clock_timestamp()""".stripMargin
        )
        try
          statement.setString(1, key.value)
          statement.setBytes(2, value.toArray)
          statement.executeUpdate()
          ()
        finally statement.close()
      finally connection.close()
    }

  override def get(key: KvKey): ZIO[Any, Throwable, Option[KvValue]] =
    ZIO
      .attemptBlocking {
        val connection = dataSource.getConnection()
        try
          val statement = connection.prepareStatement("SELECT value FROM graviton.key_value WHERE key = ?")
          try
            statement.setString(1, key.value)
            val result = statement.executeQuery()
            try if result.next() then Option(result.getBytes(1)) else None
            finally result.close()
          finally statement.close()
        finally connection.close()
      }
      .flatMap {
        case None        => ZIO.succeed(None)
        case Some(value) =>
          ZIO
            .fromEither(KvValue.fromArray(value))
            .mapError(message =>
              new IllegalStateException(s"PostgreSQL value for '${key.value}' exceeds ${KvValue.MaxBytes} bytes: $message")
            )
            .map(Some(_))
      }

  override def delete(key: KvKey): ZIO[Any, Throwable, Unit] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection()
      try
        val statement = connection.prepareStatement("DELETE FROM graviton.key_value WHERE key = ?")
        try
          statement.setString(1, key.value)
          statement.executeUpdate()
          ()
        finally statement.close()
      finally connection.close()
    }

object PgKeyValueStore:
  val layer: ZLayer[DataSource, Nothing, KeyValueStore] =
    ZLayer.fromFunction((dataSource: DataSource) => new PgKeyValueStore(dataSource): KeyValueStore)
