package graviton.pg

import zio.*
import com.zaxxer.hikari.HikariDataSource
import com.augustnagro.magnum.*
import javax.sql.DataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration

object PgTestLayers:

  private def mkContainer: ZIO[Scope, Throwable, PostgreSQLContainer[?]] =
    ZIO.acquireRelease(
      ZIO.attempt {
        val image = sys.env.get("PG_IMAGE").getOrElse("postgres:17-alpine")
        val img   = DockerImageName.parse(image)
        val c     = new PostgreSQLContainer(img)
          .withReuse(false)
          .withInitScript("ddl.sql") // runs from src/test/resources
          .withStartupAttempts(3)
          .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(90)))
        c.start()
        c
      }
    )(c => ZIO.attempt(c.stop()).ignore)

  private def mkDataSource(c: PostgreSQLContainer[?]): ZIO[Scope, Throwable, HikariDataSource] =
    ZIO.acquireRelease(
      ZIO.attempt {
        val ds = new HikariDataSource()
        // IMPORTANT: trust Testcontainers' URL (includes host + mapped port + params)
        val url = {
          val u = c.getJdbcUrl
          if (u.contains("sslmode=")) u
          else u + (if (u.contains("?")) "&" else "?") + "sslmode=disable"
        }
        ds.setJdbcUrl(url)
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
        // optional, can help on noisy runners:
        ds.setConnectionTestQuery("SELECT 1")
        ds
      }
    )(ds => ZIO.attempt(ds.close()).ignore)

  val transactorLayer: ZLayer[Any, Throwable, Transactor] =
    ZLayer.scoped {
      for {
        container <- mkContainer
        ds        <- mkDataSource(container)
        // sanity check after container init script has run
        _         <- ZIO.attemptBlocking {
                       val c = ds.getConnection
                       try c.prepareStatement("select 1").execute()
                       finally c.close()
                     }
      } yield Transactor(ds)
    }
