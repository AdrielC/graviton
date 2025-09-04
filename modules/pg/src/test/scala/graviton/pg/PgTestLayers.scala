package graviton.pg

import zio.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.postgresql.ds.PGSimpleDataSource
import javax.sql.DataSource
import com.augustnagro.magnum.*
import scala.io.Source

object PgTestLayers:
  private def startEmbedded: ZIO[Scope, Throwable, EmbeddedPostgres] =
    ZIO.acquireRelease(ZIO.attempt(EmbeddedPostgres.start()))(pg => ZIO.attempt(pg.close()).ignore)

  private def runDdl(ds: DataSource): Task[Unit] =
    ZIO.attemptBlocking {
      val sql  = Source.fromResource("ddl.sql").mkString
      val conn = ds.getConnection()
      val stmt = conn.createStatement()
      stmt.execute(sql)
      // intentionally not closing to keep connection alive for tests
    }

  val transactorLayer: ZLayer[Any, Throwable, Transactor] =
    ZLayer.scoped {
      for
        pg <- startEmbedded
        ds  <- ZIO.attempt {
                val d = PGSimpleDataSource()
                d.setURL(pg.getJdbcUrl("postgres", "postgres"))
                d
              }
        _  <- runDdl(ds)
      yield Transactor(ds)
    }
