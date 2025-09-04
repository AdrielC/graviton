package graviton.pg

import zio.*
import com.zaxxer.hikari.HikariDataSource
import com.augustnagro.magnum.*
import javax.sql.DataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

object PgTestLayers:

  private def mkContainer: ZIO[Scope, Throwable, PostgreSQLContainer[?]] =
    ZIO.acquireRelease(
      ZIO.attempt {
        val img = DockerImageName.parse("postgres:17-alpine") // PG 17
        val c   = new PostgreSQLContainer(img)
          .withReuse(false)
          .withInitScript("ddl.sql")
          .withStartupAttempts(3)
        c.start()
        c
      }
    )(c => ZIO.attempt(c.stop()).ignore)

  private def mkDataSource(c: PostgreSQLContainer[?]): ZIO[Scope, Throwable, HikariDataSource] =
    ZIO.acquireRelease(
      ZIO.attempt {
        // enable debug before pool init
        System.setProperty("com.zaxxer.hikari.level", "DEBUG")

        val ds = new HikariDataSource()
        ds.setJdbcUrl(c.getJdbcUrl)
        ds.setUsername(c.getUsername)
        ds.setPassword(c.getPassword)
        ds.setMaximumPoolSize(4)
        ds.setMinimumIdle(1)
        ds.setAutoCommit(true)
        ds.setKeepaliveTime(30_000)     // keep connections warm on CI
        ds.setIdleTimeout(120_000)
        ds.setMaxLifetime(600_000)
        ds.setValidationTimeout(5_000)
        ds.setConnectionTimeout(10_000)
        ds.setPoolName("pg-tests")
        ds.setConnectionTestQuery("SELECT 1")
        ds.setLeakDetectionThreshold(10_000) // turn on temporarily if chasing leaks
        ds
      }
    )(ds => ZIO.attempt(ds.close()).ignore)

  val transactorLayer: ZLayer[Any, Throwable, Transactor] =
    ZLayer.scoped {
      for {
        container <- mkContainer
        ds        <- mkDataSource(container)
        _         <- ZIO.attemptBlocking {
                       val c = ds.getConnection
                       try c.prepareStatement("select 1").execute()
                       finally c.close()
                     }
      } yield Transactor(ds)
    }