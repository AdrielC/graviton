package graviton.integration.shardcake

import com.devsisters.shardcake.interfaces.Storage
import com.devsisters.shardcake.{Pod, PodAddress, ShardId}
import graviton.runtime.upload.{UploadNodeHost, UploadNodePort}
import zio.*
import zio.stream.ZStream

import java.sql.{Connection, PreparedStatement, ResultSet}
import javax.sql.DataSource

/** Durable Shardcake assignment and pod registry backed by PostgreSQL. */
object PgShardcakeStorage:
  sealed trait Error extends Exception

  object Error:
    final case class InvalidPodAddress(reason: String) extends Exception(s"invalid Shardcake pod address: $reason") with Error

    final case class InvalidPodVersion(version: String)
        extends Exception(s"Shardcake pod version must contain 1..64 characters, found ${version.length}")
        with Error

  final case class Config(pollInterval: Duration):
    def validate: Either[String, Config] =
      Either.cond(
        pollInterval >= 100.millis && pollInterval <= 1.minute,
        this,
        "pollInterval must be within 100ms..1m",
      )

  object Config:
    val Default: Config = Config(1.second)

    val config: zio.Config[Config] =
      zio.Config
        .duration("poll-interval")
        .withDefault(Default.pollInterval)
        .map(Config.apply)
        .mapOrFail(_.validate.left.map(message => zio.Config.Error.InvalidData(Chunk.empty, message)))
        .nested("storage")
        .nested("shardcake")
        .nested("graviton")

    val layer: ZLayer[Any, zio.Config.Error, Config] = ZLayer.fromZIO(ZIO.config(config))

  private final case class AssignmentRow(shardId: ShardId, owner: Option[PodAddress])
  private final case class PodRow(address: PodAddress, version: String)

  val live: ZLayer[DataSource & Config, Nothing, Storage] =
    ZLayer.fromZIO {
      for
        dataSource <- ZIO.service[DataSource]
        config     <- ZIO.service[Config]
      yield Live(dataSource, config)
    }

  def layer(config: Config = Config.Default): ZLayer[DataSource, Nothing, Storage] =
    ZLayer.succeed(config) >>> live

  private final case class Live(dataSource: DataSource, config: Config) extends Storage:
    override def getAssignments: Task[Map[ShardId, Option[PodAddress]]] =
      queryAssignments.map(_.map(row => row.shardId -> row.owner).toMap)

    override def saveAssignments(assignments: Map[ShardId, Option[PodAddress]]): Task[Unit] =
      transaction { connection =>
        lock(connection)
        execute(connection, "DELETE FROM graviton.shardcake_assignment")
        val statement = connection.prepareStatement(
          "INSERT INTO graviton.shardcake_assignment (shard_id, pod_host, pod_port) VALUES (?, ?, ?)"
        )
        try
          assignments.toVector.sortBy(_._1).foreach { case (shardId, owner) =>
            statement.setInt(1, shardId)
            owner match
              case Some(address) =>
                validateAddress(address)
                statement.setString(2, address.host)
                statement.setInt(3, address.port)
              case None          =>
                statement.setNull(2, java.sql.Types.VARCHAR)
                statement.setNull(3, java.sql.Types.INTEGER)
            statement.addBatch()
          }
          statement.executeBatch()
          ()
        finally statement.close()
      }

    override def assignmentsStream: ZStream[Any, Throwable, Map[ShardId, Option[PodAddress]]] =
      (ZStream.fromZIO(getAssignments) ++
        ZStream.repeatZIO(getAssignments).schedule(Schedule.spaced(config.pollInterval))).changes

    override def getPods: Task[Map[PodAddress, Pod]] =
      queryPods.map(_.map(row => row.address -> Pod(row.address, row.version)).toMap)

    override def savePods(pods: Map[PodAddress, Pod]): Task[Unit] =
      transaction { connection =>
        lock(connection)
        execute(connection, "DELETE FROM graviton.shardcake_pod")
        val statement = connection.prepareStatement(
          "INSERT INTO graviton.shardcake_pod (pod_host, pod_port, server_version) VALUES (?, ?, ?)"
        )
        try
          pods.values.toVector.sortBy(pod => (pod.address.host, pod.address.port)).foreach { pod =>
            validateAddress(pod.address)
            if pod.version.isEmpty || pod.version.length > 64 then throw Error.InvalidPodVersion(pod.version)
            statement.setString(1, pod.address.host)
            statement.setInt(2, pod.address.port)
            statement.setString(3, pod.version)
            statement.addBatch()
          }
          statement.executeBatch()
          ()
        finally statement.close()
      }

    private def queryAssignments: Task[Vector[AssignmentRow]] =
      query("SELECT shard_id, pod_host, pod_port FROM graviton.shardcake_assignment ORDER BY shard_id") { result =>
        val host  = Option(result.getString("pod_host"))
        val owner = host.map { value =>
          val address = PodAddress(value, result.getInt("pod_port"))
          validateAddress(address)
          address
        }
        AssignmentRow(result.getInt("shard_id"), owner)
      }

    private def queryPods: Task[Vector[PodRow]] =
      query("SELECT pod_host, pod_port, server_version FROM graviton.shardcake_pod ORDER BY pod_host, pod_port") { result =>
        val address = PodAddress(result.getString("pod_host"), result.getInt("pod_port"))
        validateAddress(address)
        PodRow(address, result.getString("server_version"))
      }

    private def query[A](sql: String)(read: ResultSet => A): Task[Vector[A]] =
      ZIO.scoped {
        ZIO.fromAutoCloseable(ZIO.attemptBlocking(dataSource.getConnection)).flatMap { connection =>
          ZIO.attemptBlocking {
            val statement = connection.prepareStatement(sql)
            try
              val result = statement.executeQuery()
              try
                val builder = Vector.newBuilder[A]
                while result.next() do builder += read(result)
                builder.result()
              finally result.close()
            finally statement.close()
          }
        }
      }

    private def transaction[A](operation: Connection => A): Task[A] =
      ZIO.scoped {
        ZIO.fromAutoCloseable(ZIO.attemptBlocking(dataSource.getConnection)).flatMap { connection =>
          ZIO.attemptBlocking {
            connection.setAutoCommit(false)
            try
              val result = operation(connection)
              connection.commit()
              result
            catch
              case error: Throwable =>
                connection.rollback()
                throw error
          }
        }
      }

    private def lock(connection: Connection): Unit =
      execute(connection, "SELECT pg_advisory_xact_lock(7094116700425191201)")

    private def execute(connection: Connection, sql: String): Unit =
      val statement: PreparedStatement = connection.prepareStatement(sql)
      try
        statement.execute()
        ()
      finally statement.close()

    private def validateAddress(address: PodAddress): Unit =
      UploadNodeHost.either(address.host).fold(reason => throw Error.InvalidPodAddress(reason), _ => ())
      UploadNodePort.either(address.port).fold(reason => throw Error.InvalidPodAddress(reason), _ => ())
