package graviton.pg

import zio.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.postgresql.ds.PGSimpleDataSource
import com.augustnagro.magnum.*
import javax.sql.DataSource
import scala.io.Source

object PgTestLayers:
  private def startContainer: ZIO[Scope, Throwable, PostgreSQLContainer[?]] =
    ZIO.acquireRelease {
      ZIO.attempt {
        val c =
          new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
        c.start()
        c
      }
    }(c => ZIO.attempt(c.stop()).ignore)

  private def runDdl(ds: DataSource): Task[Unit] =
    ZIO.attemptBlocking {
      val sql = Source.fromResource("ddl.sql").mkString
      val conn = ds.getConnection()
      try
        val stmt = conn.createStatement()
        stmt.execute(sql)
        stmt.close()
      finally conn.close()
    }

  val transactorLayer: ZLayer[Any, Throwable, Transactor] =
    ZLayer.scoped {
      for
        c <- startContainer
        ds <- ZIO.attempt {
          val d = PGSimpleDataSource()
          d.setURL(c.getJdbcUrl)
          d.setUser(c.getUsername)
          d.setPassword(c.getPassword)
          d
        }
        _ <- runDdl(ds)
      yield Transactor(ds)
    }
