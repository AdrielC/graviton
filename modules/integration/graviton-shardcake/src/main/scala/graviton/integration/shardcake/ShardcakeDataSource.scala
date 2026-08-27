package graviton.integration.shardcake

import org.postgresql.ds.PGSimpleDataSource
import zio.*

import javax.sql.DataSource

object ShardcakeDataSource:
  final case class Config(
    jdbcUrl: String,
    username: String,
    password: String,
  ):
    def validate: Either[String, Config] =
      for
        _ <- Either.cond(jdbcUrl.startsWith("jdbc:postgresql://"), (), "jdbcUrl must use jdbc:postgresql://")
        _ <- Either.cond(username.nonEmpty, (), "username must be nonempty")
        _ <- Either.cond(password.nonEmpty, (), "password must be nonempty")
      yield this

  object Config:
    val config: zio.Config[Config] =
      (zio.Config.string("jdbc-url") ++ zio.Config.string("username") ++ zio.Config.string("password"))
        .map { case (url, user, pass) => Config(url, user, pass) }
        .mapOrFail(_.validate.left.map(message => zio.Config.Error.InvalidData(Chunk.empty, message)))
        .nested("postgres")
        .nested("shardcake")
        .nested("graviton")

    val layer: ZLayer[Any, zio.Config.Error, Config] = ZLayer.fromZIO(ZIO.config(config))

  val live: ZLayer[Config, Nothing, DataSource] =
    ZLayer.fromFunction { (config: Config) =>
      val source = PGSimpleDataSource()
      source.setURL(config.jdbcUrl)
      source.setUser(config.username)
      source.setPassword(config.password)
      source: DataSource
    }
