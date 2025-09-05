package graviton.pg

import zio.*
import com.zaxxer.hikari.HikariDataSource
import com.augustnagro.magnum.magzio.TransactorZIO
import javax.sql.DataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration
import java.nio.file.Path
import org.testcontainers.containers.PostgreSQLContainerProvider
import zio.prelude.fx.*
import zio.prelude.*

object PgTestLayers:

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
    startupTimeout: Long,
  )
  object PgTestConfig:

    val layer = ZLayer.fromZIO(ZIO.config[PgTestConfig])

    def provider =
      ConfigProvider
        .fromMap(
          Map(
            "image"           -> "postgres:17-alpine",
            "username"        -> "postgres",
            "password"        -> "postgres",
            "database"        -> "postgres",
            "initScript"      -> "ddl.sql",
            "startupAttempts" -> "3",
            "startupTimeout"  -> "90",
          )
        )
        .orElse(ConfigProvider.envProvider)
        .orElse(ConfigProvider.propsProvider)
        .nested("pg")

    given config: Config[PgTestConfig] =

      val image           = Config.string("image").withDefault("postgres")
      val tag             = Config.string("tag").withDefault("17-alpine")
      val registry        = Config.string("registry").optional
      val repository      = Config.string("repository").optional
      val username        = Config.string("username").withDefault("postgres")
      val password        = Config.string("password").withDefault("postgres")
      val database        = Config.string("database").withDefault("postgres")
      val initScript      = Config.string("initScript").withDefault("ddl.sql").map(Path.of(_)).optional
      val startupAttempts = Config.int("startupAttempts").withDefault(3)
      val startupTimeout  = Config.long("startupTimeout").withDefault(90L)

      (image ++ tag ++ registry ++ repository ++ username ++ password ++ database ++ initScript ++ startupAttempts ++ startupTimeout).map:
        case (image, tag, registry, repository, username, password, database, initScript, startupAttempts, startupTimeout) =>
          PgTestConfig(
            image = image,
            tag = tag,
            registry = registry,
            repository = repository,
            username = username,
            password = password,
            database = database,
            initScript = initScript,
            startupAttempts = startupAttempts,
            startupTimeout = startupTimeout,
          )

    end config

  val containerProvider: ZLayer[Any, Throwable, PostgreSQLContainerProvider] =
    ZLayer.succeed(new PostgreSQLContainerProvider())

  val getImageName: ZPure[Nothing, Unit, DockerImageName, PgTestConfig, Throwable, DockerImageName] =
    for
      config <- State.service[Unit, PgTestConfig]
      imgN   <- ZPure.attempt(DockerImageName.parse(config.image))
      _      <- State.set(imgN)
      _      <- State.update((_: DockerImageName).withTag(config.tag))
      _      <- State.update((d: DockerImageName) => config.registry.fold(d)(d.withRegistry))
      _      <- State.update((d: DockerImageName) => config.repository.fold(d)(d.withRepository))
      img    <- State.get[DockerImageName]
    yield img

  private def mkContainer: ZLayer[PgTestConfig, Throwable, PostgreSQLContainer[?]] =
    ZLayer.scoped:
      for
        imgN      <- getImageName.toZIO
        config    <- ZIO.service[PgTestConfig]
        container <- ZIO.acquireRelease(
                       ZIO.attempt {
                         val c = PostgreSQLContainer(imgN)
                         c.withUsername(config.username)
                         c.withPassword(config.password)
                         c.withDatabaseName(config.database)
                         config.initScript.foreach(path => c.withInitScript(path.toString))
                         c.withStartupAttempts(config.startupAttempts)
                         c.withStartupTimeout(Duration.ofSeconds(config.startupTimeout))
                         c.withReuse(true)
                         c.waitingFor(Wait.forListeningPort().withStartupTimeout(config.startupTimeout.seconds))
                         c.start()
                         c
                       }
                     )(c => ZIO.attempt(c.stop()).orDie)
      yield container

  private def mkDataSource: ZLayer[PostgreSQLContainer[?], Throwable, HikariDataSource] =
    ZLayer.scoped:
      for
        container <- ZIO.service[PostgreSQLContainer[?]]
        ds        <- ZIO.acquireRelease(
                       ZIO.attempt {
                         val ds  = new HikariDataSource()
                         // IMPORTANT: trust Testcontainers' URL (includes host + mapped port + params)
                         val url = {
                           val u = container.getJdbcUrl
                           if (u.contains("sslmode=")) u
                           else u + (if (u.contains("?")) "&" else "?") + "sslmode=disable"
                         }
                         ds.setJdbcUrl(url)
                         ds.setUsername(container.getUsername)
                         ds.setPassword(container.getPassword)
                         ds.setMaximumPoolSize(4)
                         ds.setMinimumIdle(1)
                         ds.setAutoCommit(true)
                         ds.setKeepaliveTime(30_000) // keep connections warm on CI
                         ds.setIdleTimeout(120_000)
                         ds.setMaxLifetime(600_000)
                         ds.setValidationTimeout(5_000)
                         ds.setConnectionTimeout(10_000)
                         ds.setPoolName("pg-tests")
                         // optional, can help on noisy runners:
                         ds.setConnectionTestQuery("SELECT 1")
                         ds
                       }
                     )(ds => ZIO.attempt(ds.close()).orDie)
      yield ds

  val transactorLayer: ZLayer[DataSource, Nothing, TransactorZIO] =
    TransactorZIO.layer

  val layer: ZLayer[PgTestConfig, Throwable, TransactorZIO] =
    mkContainer >>> mkDataSource >>> transactorLayer
