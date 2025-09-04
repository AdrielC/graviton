package graviton.pg

import zio.*
import com.zaxxer.hikari.HikariDataSource
import com.augustnagro.magnum.*
import javax.sql.DataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import scala.io.Source

object PgTestLayers:
  private def mkContainer: Task[PostgreSQLContainer[?]] =
    ZIO.attempt {
      val image = DockerImageName.parse("postgres:16-alpine")
      val c = new PostgreSQLContainer(image)
      c.start()
      c
    }

  private def mkDataSource(c: PostgreSQLContainer[?]): Task[HikariDataSource] =
    ZIO.attempt {
      val ds = new HikariDataSource()
      ds.setJdbcUrl(c.getJdbcUrl)
      ds.setUsername(c.getUsername)
      ds.setPassword(c.getPassword)
      ds.setMaximumPoolSize(4)
      ds
    }

  private def runDdl(ds: DataSource): Task[Unit] =
    ZIO.attemptBlocking {
      val sql = Source.fromResource("ddl.sql").mkString
      val conn = ds.getConnection()
      val stmt = conn.createStatement()
      stmt.execute(sql)
      // intentionally keep resources open for test lifetime
    }

  val transactorLayer: ZLayer[Any, Throwable, Transactor] =
    ZLayer.fromZIO {
      for
        container <- mkContainer
        ds <- mkDataSource(container)
        _ <- runDdl(ds)
      yield Transactor(ds)
    }
