package graviton.integration.shardcake

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import zio.*

import javax.sql.DataSource

object ShardcakeDataSource:
  final case class Config(
    jdbcUrl: String,
    username: String,
    password: String,
    maximumPoolSize: Int,
    minimumIdle: Int,
  ):
    def validate: Either[String, Config] =
      for
        _ <- Either.cond(jdbcUrl.startsWith("jdbc:postgresql://"), (), "jdbcUrl must use jdbc:postgresql://")
        _ <- Either.cond(username.nonEmpty, (), "username must be nonempty")
        _ <- Either.cond(password.nonEmpty, (), "password must be nonempty")
        _ <- Either.cond(maximumPoolSize > 0, (), "maximumPoolSize must be positive")
        _ <- Either.cond(minimumIdle >= 0 && minimumIdle <= maximumPoolSize, (), "minimumIdle must be between zero and maximumPoolSize")
      yield this

  object Config:
    val config: zio.Config[Config] =
      (zio.Config.string("jdbc-url") ++
        zio.Config.string("username") ++
        zio.Config.string("password") ++
        zio.Config.int("maximum-pool-size").withDefault(16) ++
        zio.Config.int("minimum-idle").withDefault(2))
        .map { case (url, user, pass, maximumPoolSize, minimumIdle) => Config(url, user, pass, maximumPoolSize, minimumIdle) }
        .mapOrFail(_.validate.left.map(message => zio.Config.Error.InvalidData(Chunk.empty, message)))
        .nested("postgres")
        .nested("shardcake")
        .nested("graviton")

    val layer: ZLayer[Any, zio.Config.Error, Config] = ZLayer.fromZIO(ZIO.config(config))

  val live: ZLayer[Config, Throwable, DataSource] =
    ZLayer.scoped {
      ZIO.service[Config].flatMap(acquire)
    }

  /** Node layer that also exports fixed-cardinality Shardcake pool pressure. */
  val liveInstrumented: ZLayer[Config & MetricsRegistry, Throwable, DataSource] =
    ZLayer.scoped {
      for
        config  <- ZIO.service[Config]
        metrics <- ZIO.service[MetricsRegistry]
        pool    <- acquire(config)
        _       <- publishMetrics(pool, metrics).repeat(Schedule.spaced(10.seconds)).forkScoped
      yield pool
    }

  private def acquire(config: Config): ZIO[Scope, Throwable, DataSource] =
    ZIO.acquireRelease(
      ZIO.attempt {
        val hikari = new HikariConfig()
        hikari.setPoolName("graviton-shardcake-postgres")
        hikari.setJdbcUrl(config.jdbcUrl)
        hikari.setUsername(config.username)
        hikari.setPassword(config.password)
        hikari.setMaximumPoolSize(config.maximumPoolSize)
        hikari.setMinimumIdle(config.minimumIdle)
        hikari.setConnectionTimeout(10000L)
        hikari.setValidationTimeout(5000L)
        hikari.setIdleTimeout(600000L)
        hikari.setMaxLifetime(1800000L)
        hikari.setKeepaliveTime(120000L)
        hikari.setInitializationFailTimeout(1L)
        hikari.addDataSourceProperty("tcpKeepAlive", "true")
        new HikariDataSource(hikari): DataSource
      }
    ) {
      case pool: HikariDataSource => ZIO.attempt(pool.close()).orDie
      case _                      => ZIO.unit
    }

  private def publishMetrics(dataSource: DataSource, metrics: MetricsRegistry): UIO[Unit] =
    dataSource match
      case pool: HikariDataSource =>
        val runtime = pool.getHikariPoolMXBean
        val tags    = Map("pool" -> "shardcake")
        metrics.gauge(MetricKeys.PostgresPoolConnections, runtime.getActiveConnections.toDouble, tags + ("state" -> "active")) *>
          metrics.gauge(MetricKeys.PostgresPoolConnections, runtime.getIdleConnections.toDouble, tags + ("state" -> "idle")) *>
          metrics.gauge(MetricKeys.PostgresPoolConnections, runtime.getTotalConnections.toDouble, tags + ("state" -> "total")) *>
          metrics.gauge(MetricKeys.PostgresPoolConnections, pool.getMaximumPoolSize.toDouble, tags + ("state" -> "maximum")) *>
          metrics.gauge(MetricKeys.PostgresPoolAwaiting, runtime.getThreadsAwaitingConnection.toDouble, tags)
      case _                      => ZIO.unit
