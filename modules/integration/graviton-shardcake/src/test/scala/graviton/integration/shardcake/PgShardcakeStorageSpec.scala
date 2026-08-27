package graviton.integration.shardcake

import com.devsisters.shardcake.interfaces.Storage
import com.devsisters.shardcake.{Pod, PodAddress, ShardId}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.*
import zio.test.*

import javax.sql.DataSource

object PgShardcakeStorageSpec extends ZIOSpecDefault:
  private val dataSourceLayer: ZLayer[Any, Throwable, DataSource] =
    ZLayer.scoped {
      ZIO
        .acquireRelease(ZIO.attemptBlocking(EmbeddedPostgres.builder().setPort(0).start()))(database =>
          ZIO.attemptBlocking(database.close()).orDie
        )
        .map(_.getPostgresDatabase)
    }

  override def spec: Spec[TestEnvironment, Any] =
    suite("PgShardcakeStorage")(
      test("persists assignments and pods across independent adapters") {
        val owner                                         = PodAddress("node-a", 54321)
        val assignments: Map[ShardId, Option[PodAddress]] =
          Map(1 -> Some(owner), 2 -> None)
        val pods                                          = Map(owner -> Pod(owner, "2.8.1"))

        for
          dataSource          <- ZIO.service[DataSource]
          _                   <- initialize(dataSource)
          writer              <- storage(dataSource)
          reader              <- storage(dataSource)
          _                   <- writer.saveAssignments(assignments)
          _                   <- writer.savePods(pods)
          restoredAssignments <- reader.getAssignments
          restoredPods        <- reader.getPods
        yield assertTrue(
          restoredAssignments == assignments,
          restoredPods == pods,
        )
      },
      test("publishes cross-process assignment changes through polling") {
        val owner                                     = PodAddress("node-b", 54322)
        val changed: Map[ShardId, Option[PodAddress]] = Map(7 -> Some(owner))

        for
          dataSource <- ZIO.service[DataSource]
          _          <- initialize(dataSource)
          writer     <- storage(dataSource)
          observer   <- storage(dataSource)
          subscribed <- Promise.make[Nothing, Unit]
          awaiting   <- observer.assignmentsStream
                          .tap(_ => subscribed.succeed(()))
                          .drop(1)
                          .runHead
                          .fork
          _          <- subscribed.await
          _          <- writer.saveAssignments(changed)
          observed   <- awaiting.join.timeoutFail(new IllegalStateException("assignment update was not observed"))(3.seconds)
        yield assertTrue(observed.contains(changed))
      },
      test("holds one manager lease and releases it with scope") {
        for
          dataSource <- ZIO.service[DataSource]
          competing  <- (ZIO.service[ShardcakeManagerLease] *>
                          ZIO
                            .service[ShardcakeManagerLease]
                            .provide(ZLayer.succeed(dataSource), ShardcakeManagerLease.live)
                            .exit)
                          .provide(ZLayer.succeed(dataSource), ShardcakeManagerLease.live)
          reacquired <- ZIO
                          .service[ShardcakeManagerLease]
                          .unit
                          .provide(ZLayer.succeed(dataSource), ShardcakeManagerLease.live)
                          .exit
          leaseDenied = competing match
                          case Exit.Failure(cause) => cause.failureOption.contains(ShardcakeManagerLease.Error.AlreadyHeld())
                          case Exit.Success(_)     => false
        yield assertTrue(
          competing.isFailure,
          leaseDenied,
          reacquired.isSuccess,
        )
      },
    ).provideShared(dataSourceLayer) @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds) @@ TestAspect.sequential

  private def storage(dataSource: DataSource): UIO[Storage] =
    ZIO
      .service[Storage]
      .provide(
        ZLayer.succeed(dataSource),
        PgShardcakeStorage.layer(PgShardcakeStorage.Config(100.millis)),
      )

  private def initialize(dataSource: DataSource): Task[Unit] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection
      try
        val statement = connection.createStatement()
        try
          statement.execute("CREATE SCHEMA IF NOT EXISTS graviton")
          statement.execute(
            """CREATE TABLE IF NOT EXISTS graviton.shardcake_assignment (
              |  shard_id integer PRIMARY KEY CHECK (shard_id >= 1),
              |  pod_host varchar(120),
              |  pod_port integer,
              |  CONSTRAINT shardcake_assignment_owner_pair CHECK (
              |    (pod_host IS NULL AND pod_port IS NULL) OR
              |    (pod_host IS NOT NULL AND pod_port BETWEEN 1 AND 65535)
              |  )
              |)""".stripMargin
          )
          statement.execute(
            """CREATE TABLE IF NOT EXISTS graviton.shardcake_pod (
              |  pod_host varchar(120) NOT NULL,
              |  pod_port integer NOT NULL CHECK (pod_port BETWEEN 1 AND 65535),
              |  server_version varchar(64) NOT NULL CHECK (length(server_version) >= 1),
              |  PRIMARY KEY (pod_host, pod_port)
              |)""".stripMargin
          )
          val _ = statement.execute("TRUNCATE graviton.shardcake_assignment, graviton.shardcake_pod")
        finally statement.close()
      finally connection.close()
    }
