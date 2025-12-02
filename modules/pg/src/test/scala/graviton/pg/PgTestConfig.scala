package graviton.pg

import zio.*
import java.nio.file.Path
import java.time.Duration

case class PgTestConfig(
  image: String,
  tag: String,
  registry: Option[String],
  repository: Option[String],
  username: String,
  password: String,
  database: String,
  initScript: Option[Path],
  startupAttempts: Int,
  startupTimeout: Duration,
)

object PgTestConfig:

  val layer: ZLayer[Any, Config.Error, PgTestConfig] =
    ZLayer.succeed(provider) >>>
      ZLayer.fromZIO(ZIO.config[PgTestConfig])

  def provider =
    ConfigProvider
      .fromMap(
        Map(
          "image"   -> "postgres",
          "tag"             -> "17",
          "username"        -> "postgres",
          "password"        -> "postgres",
          "database"        -> "postgres",
          "initScript"      -> "../../ddl.sql",
          "startupAttempts" -> "3",
          "startupTimeout"  -> "10 minutes",
        )
      )
      .orElse(ConfigProvider.envProvider)
      .orElse(ConfigProvider.propsProvider)
      .nested("pg")

  given config: Config[PgTestConfig] =

    val image           = Config.string("image").withDefault("postgres")
    val tag             = Config.string("tag").withDefault("17")
    val registry        = Config.string("registry").optional
    val repository      = Config.string("repository").optional
    val username        = Config.string("username").withDefault("postgres")
    val password        = Config.string("password").withDefault("postgres")
    val database        = Config.string("database").withDefault("postgres")
    val initScript      = Config.string("initScript").withDefault("../../ddl.sql").map(Path.of(_)).optional
    val startupAttempts = Config.int("startupAttempts").withDefault(3)
    val startupTimeout  = Config.duration("startupTimeout").withDefault(10.minutes)

    (
      image ++ 
      tag ++ 
      registry ++ 
      repository ++ 
      username ++ 
      password ++ 
      database ++ 
      initScript ++ 
      startupAttempts ++ 
      startupTimeout
    )
    .nested("pg")
    .map(PgTestConfig.apply)

  end config
end PgTestConfig
