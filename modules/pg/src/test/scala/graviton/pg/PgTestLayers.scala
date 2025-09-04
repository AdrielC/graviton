package graviton.pg

import zio.*
import com.zaxxer.hikari.HikariDataSource
import com.augustnagro.magnum.*
import javax.sql.DataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import scala.io.Source

object PgTestLayers:

  private def mkContainer: ZIO[Scope, Throwable, PostgreSQLContainer[?]] =
    ZIO.acquireRelease(
      ZIO.attempt {
        val image = DockerImageName.parse("postgres:16-alpine")
        val c = new PostgreSQLContainer(image)
        // be explicit; GHA can be flaky with reuse
        c.withReuse(false)
        c.start()
        c
      }
    )(c => ZIO.attempt(c.stop()).ignore)

  private def mkDataSource(
      c: PostgreSQLContainer[?]
  ): ZIO[Scope, Throwable, HikariDataSource] =
    ZIO.acquireRelease(
      ZIO.attempt {
        val ds = new HikariDataSource()
        ds.setJdbcUrl(c.getJdbcUrl)
        ds.setUsername(c.getUsername)
        ds.setPassword(c.getPassword)
        ds.setMaximumPoolSize(4)
        ds.setMinimumIdle(1)
        ds.setAutoCommit(true)
        // CI-friendly: keep connections warm, avoid mysterious retires
        ds.setKeepaliveTime(30_000) // 30s
        ds.setIdleTimeout(120_000) // 2m
        ds.setMaxLifetime(600_000) // 10m
        ds
      }
    )(ds => ZIO.attempt(ds.close()).ignore)

  private def runDdl(ds: DataSource): Task[Unit] =
    ZIO.attemptBlocking {
      val src = Source.fromResource("ddl.sql")
      try {
        val sql = src.mkString
        val conn = ds.getConnection
        try {
          conn.setAutoCommit(true)
          val stmt = conn.createStatement()
          try stmt.execute(sql)
          finally stmt.close()
        } finally conn.close()
      } finally src.close()
    }

  val transactorLayer: ZLayer[Any, Throwable, Transactor] =
    ZLayer.scoped {
      for
        container <- mkContainer
        ds <- mkDataSource(container)
        _ <- runDdl(ds)
        // smoke check the pool after DDL
        _ <- ZIO.attemptBlocking {
          val c = ds.getConnection
          try c.prepareStatement("select 1").execute()
          finally c.close()
        }
      yield Transactor(ds)
    }
