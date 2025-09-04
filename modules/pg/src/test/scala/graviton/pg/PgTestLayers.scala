package graviton.pg

import zio.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.postgresql.ds.PGSimpleDataSource
import com.augustnagro.magnum.*
import javax.sql.DataSource
import scala.io.Source

object PgTestLayers:
  private def startEmbedded: ZIO[Scope, Throwable, EmbeddedPostgres] =
    ZIO.acquireRelease(ZIO.attempt(EmbeddedPostgres.start()))(pg =>
      ZIO.attempt(pg.close()).ignore
    )

  private def mkDataSource(pg: EmbeddedPostgres): Task[PGSimpleDataSource] =
    ZIO.attempt {
      val ds = PGSimpleDataSource()
      ds.setURL(pg.getJdbcUrl("postgres", "postgres"))
      ds.setUser("postgres")
      ds.setPassword("postgres")
      ds
    }

  private def runDdl(ds: DataSource): Task[Unit] =
    ZIO.attemptBlocking {
      val sql = Source.fromResource("ddl.sql").mkString
      val conn = ds.getConnection()
      try {
        val stmt = conn.createStatement()
        try stmt.execute(sql)
        finally stmt.close()
      } finally conn.close()
    }

  val transactorLayer: ZLayer[Any, Throwable, Transactor] =
    ZLayer.scoped {
      for
        pg <- startEmbedded
        ds <- mkDataSource(pg)
        _ <- runDdl(ds)
      yield Transactor(ds)
    }
