package graviton.backend.pg

import graviton.core.keys.BinaryKey
import graviton.core.locator.BlobLocator
import graviton.runtime.indexes.ReplicaIndex
import zio.{Task, ZIO, ZLayer}

import java.net.URI
import java.sql.Connection
import javax.sql.DataSource

/** Transactional Postgres replica catalog for blob and block content keys. */
final class PgReplicaIndex(private val dataSource: DataSource) extends ReplicaIndex:
  override def replicas(key: BinaryKey): ZIO[Any, Throwable, Set[BlobLocator]] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection()
      try
        val statement = connection.prepareStatement(
          "SELECT locator FROM graviton.replica_index WHERE key_kind = ? AND alg = ?::core.hash_alg AND hash_bytes = ? AND byte_length = ?"
        )
        try
          bindKey(statement, key)
          val result = statement.executeQuery()
          try
            val values = Set.newBuilder[BlobLocator]
            while result.next() do values += parseLocator(result.getString(1))
            values.result()
          finally result.close()
        finally statement.close()
      finally connection.close()
    }

  override def update(key: BinaryKey, locators: Set[BlobLocator]): ZIO[Any, Throwable, Unit] =
    transaction { connection =>
      ZIO.attemptBlocking {
        val delete = connection.prepareStatement(
          "DELETE FROM graviton.replica_index WHERE key_kind = ? AND alg = ?::core.hash_alg AND hash_bytes = ? AND byte_length = ?"
        )
        try
          bindKey(delete, key)
          delete.executeUpdate()
        finally delete.close()

        val insert = connection.prepareStatement(
          "INSERT INTO graviton.replica_index (key_kind, alg, hash_bytes, byte_length, locator) VALUES (?, ?::core.hash_alg, ?, ?, ?)"
        )
        try
          locators.toList.sortBy(_.render).foreach { locator =>
            bindKey(insert, key)
            insert.setString(5, locator.render)
            insert.addBatch()
          }
          if locators.nonEmpty then
            val _ = insert.executeBatch()
        finally insert.close()
      }
    }

  private def transaction(effect: Connection => Task[Unit]): Task[Unit] =
    ZIO.scoped {
      ZIO
        .acquireRelease(ZIO.attemptBlocking(dataSource.getConnection()))(connection => ZIO.attemptBlocking(connection.close()).orDie)
        .flatMap { connection =>
          ZIO.attemptBlocking(connection.setAutoCommit(false)) *>
            effect(connection).tapBoth(
              _ => ZIO.attemptBlocking(connection.rollback()).ignore,
              _ => ZIO.attemptBlocking(connection.commit()).unit,
            )
        }
    }

  private def bindKey(statement: java.sql.PreparedStatement, key: BinaryKey): Unit =
    statement.setString(
      1,
      key match
        case _: BinaryKey.Block    => "block"
        case _: BinaryKey.Blob     => "blob"
        case _: BinaryKey.Chunk    => "chunk"
        case _: BinaryKey.Manifest => "manifest"
        case _: BinaryKey.View     => "view",
    )
    statement.setString(2, key.bits.algo.primaryName.toLowerCase.replace("-", ""))
    statement.setBytes(3, key.bits.digest.bytes)
    statement.setLong(4, key.bits.size)

  private def parseLocator(raw: String): BlobLocator =
    val uri = URI.create(raw)
    BlobLocator
      .from(uri.getScheme, uri.getHost, uri.getPath.stripPrefix("/"))
      .fold(message => throw new IllegalArgumentException(s"Invalid replica locator '$raw': $message"), identity)

object PgReplicaIndex:
  val layer: ZLayer[DataSource, Nothing, ReplicaIndex] =
    ZLayer.fromFunction((dataSource: DataSource) => new PgReplicaIndex(dataSource))
