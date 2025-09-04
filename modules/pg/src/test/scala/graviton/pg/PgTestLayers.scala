package graviton.pg

import zio.*
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import com.augustnagro.magnum.*

object PgTestLayers:
  private def startPostgresContainer: ZIO[Scope, Throwable, PostgreSQLContainer[?]] =
    ZIO.acquireRelease {
      ZIO.attempt {
        val c = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
        c.withInitScript("ddl.sql")
        c.start()
        c: PostgreSQLContainer[?]
      }
    }(c => ZIO.succeed(c.stop()))

  private def createDataSource(jdbcUrl: String, user: String, pass: String): ZIO[Scope, Throwable, HikariDataSource] =
    ZIO.acquireRelease {
      ZIO.attempt {
        val cfg = HikariConfig()
        cfg.setJdbcUrl(jdbcUrl)
        cfg.setUsername(user)
        cfg.setPassword(pass)
        HikariDataSource(cfg)
      }
    }(ds => ZIO.attempt(ds.close()).ignore)

  val transactorLayer: ZLayer[Any, Throwable, Transactor] =
    ZLayer.scoped {
      for
        c  <- startPostgresContainer
        ds <- createDataSource(c.getJdbcUrl, c.getUsername, c.getPassword)
      yield Transactor(ds)
    }
