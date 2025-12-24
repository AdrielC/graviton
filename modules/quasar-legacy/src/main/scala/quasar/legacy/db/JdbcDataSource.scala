package quasar.legacy.db

import org.postgresql.ds.PGSimpleDataSource
import zio.*

import javax.sql.DataSource

object JdbcDataSource:

  val live: ZLayer[JdbcConfig, Throwable, DataSource] =
    ZLayer.fromZIO {
      ZIO.serviceWith[JdbcConfig] { cfg =>
        ZIO.attempt {
          val ds = new PGSimpleDataSource()
          ds.setURL(cfg.url)
          ds.setUser(cfg.username)
          ds.setPassword(cfg.password)
          ds: DataSource
        }
      }
    }

