package graviton.backend.pg

import org.postgresql.ds.PGSimpleDataSource
import zio.{ZIO, ZLayer}

import javax.sql.DataSource

object PgDataSource:

  def fromEnv(
    urlEnv: String = "PG_JDBC_URL",
    userEnv: String = "PG_USERNAME",
    passEnv: String = "PG_PASSWORD",
  ): Either[String, DataSource] =
    val url  = sys.env.get(urlEnv).map(_.trim).filter(_.nonEmpty).toRight(s"Missing env '$urlEnv'")
    val user = sys.env.get(userEnv).map(_.trim).filter(_.nonEmpty).toRight(s"Missing env '$userEnv'")
    val pass = sys.env.get(passEnv).map(_.trim).filter(_.nonEmpty).toRight(s"Missing env '$passEnv'")

    for
      jdbcUrl  <- url
      username <- user
      password <- pass
    yield
      val ds = new PGSimpleDataSource()
      ds.setURL(jdbcUrl)
      ds.setUser(username)
      ds.setPassword(password)
      ds

  val layerFromEnv: ZLayer[Any, Throwable, DataSource] =
    ZLayer.fromZIO {
      ZIO.fromEither(fromEnv()).mapError(msg => new IllegalArgumentException(msg))
    }
