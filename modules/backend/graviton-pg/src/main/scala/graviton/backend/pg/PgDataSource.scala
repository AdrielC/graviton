package graviton.backend.pg

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import graviton.runtime.lifecycle.ResourceFinalizer
import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import graviton.runtime.stores.{BackendInitError, StoreBackend}
import zio.{IO, Scope, Task, UIO, ZIO, ZLayer}
import zio.{Duration, Schedule}

import javax.sql.DataSource
import scala.util.Try

object PgDataSource:

  final case class PoolStats(
    totalConnections: Int,
    activeConnections: Int,
    idleConnections: Int,
    maximumPoolSize: Int,
    awaitingConnection: Int,
  )

  final case class PoolConfig(
    maximumPoolSize: Int,
    minimumIdle: Int,
    connectionTimeoutMillis: Long,
    validationTimeoutMillis: Long,
    idleTimeoutMillis: Long,
    maxLifetimeMillis: Long,
    keepaliveTimeMillis: Long,
  ):
    def validate: Either[String, PoolConfig] =
      for
        _ <- Either.cond(maximumPoolSize > 0, (), "PG_POOL_MAX_SIZE must be positive")
        _ <- Either.cond(minimumIdle >= 0 && minimumIdle <= maximumPoolSize, (), "PG_POOL_MIN_IDLE must be between zero and maximum size")
        _ <- Either.cond(connectionTimeoutMillis >= 250L, (), "PG_POOL_CONNECTION_TIMEOUT_MS must be at least 250")
        _ <- Either.cond(
               validationTimeoutMillis >= 250L && validationTimeoutMillis < connectionTimeoutMillis,
               (),
               "PG_POOL_VALIDATION_TIMEOUT_MS must be at least 250 and below connection timeout",
             )
        _ <-
          Either.cond(idleTimeoutMillis == 0L || idleTimeoutMillis >= 10000L, (), "PG_POOL_IDLE_TIMEOUT_MS must be zero or at least 10000")
        _ <-
          Either.cond(maxLifetimeMillis == 0L || maxLifetimeMillis >= 30000L, (), "PG_POOL_MAX_LIFETIME_MS must be zero or at least 30000")
        _ <- Either.cond(
               keepaliveTimeMillis == 0L ||
                 (keepaliveTimeMillis >= 30000L && (maxLifetimeMillis == 0L || keepaliveTimeMillis < maxLifetimeMillis)),
               (),
               "PG_POOL_KEEPALIVE_TIME_MS must be zero or at least 30000 and below max lifetime",
             )
      yield this

  object PoolConfig:
    val Default: PoolConfig = PoolConfig(
      maximumPoolSize = 32,
      minimumIdle = 4,
      connectionTimeoutMillis = 10000L,
      validationTimeoutMillis = 5000L,
      idleTimeoutMillis = 600000L,
      maxLifetimeMillis = 1800000L,
      keepaliveTimeMillis = 120000L,
    )

  def fromEnv(
    urlEnv: String = "PG_JDBC_URL",
    userEnv: String = "PG_USERNAME",
    passEnv: String = "PG_PASSWORD",
  ): Either[String, DataSource] =
    connectionConfigFromEnv(urlEnv, userEnv, passEnv).flatMap { case (jdbcUrl, username, password, pool) =>
      Try(build(jdbcUrl, username, password, pool)).toEither.left.map(error =>
        Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
      )
    }

  def makeTyped(
    jdbcUrl: String,
    username: String,
    password: String,
    pool: PoolConfig = PoolConfig.Default,
  ): IO[BackendInitError, DataSource] =
    ZIO
      .fromEither(pool.validate)
      .mapError(BackendInitError.InvalidConfiguration(StoreBackend.PostgreSql, _)) *>
      ZIO
        .attempt(build(jdbcUrl, username, password, pool))
        .mapError(BackendInitError.fromThrowable(StoreBackend.PostgreSql))

  def scopedFromEnvTyped: ZIO[Scope, BackendInitError, DataSource] =
    for
      config     <- ZIO
                      .fromEither(connectionConfigFromEnv("PG_JDBC_URL", "PG_USERNAME", "PG_PASSWORD"))
                      .mapError(BackendInitError.InvalidConfiguration(StoreBackend.PostgreSql, _))
      dataSource <- ZIO.acquireRelease(makeTyped(config._1, config._2, config._3, config._4))(close)
    yield dataSource

  val layerFromEnvTyped: ZLayer[Any, BackendInitError, DataSource] =
    ZLayer.scoped(scopedFromEnvTyped)

  @deprecated("Use makeTyped to preserve the backend initialization error ADT", "0.9.0")
  def make(
    jdbcUrl: String,
    username: String,
    password: String,
    pool: PoolConfig = PoolConfig.Default,
  ): Task[DataSource] =
    makeTyped(jdbcUrl, username, password, pool)

  @deprecated("Use scopedFromEnvTyped to preserve the backend initialization error ADT", "0.9.0")
  def scopedFromEnv: ZIO[Scope, Throwable, DataSource] = scopedFromEnvTyped

  @deprecated("Use layerFromEnvTyped to preserve the backend initialization error ADT", "0.9.0")
  val layerFromEnv: ZLayer[Any, Throwable, DataSource] = layerFromEnvTyped

  def validatePoolEnvironment: Either[String, Unit] = poolConfigFromEnvironment.map(_ => ())

  private def poolConfigFromEnvironment: Either[String, PoolConfig] =
    for
      maximumPoolSize   <- integerEnv("PG_POOL_MAX_SIZE", PoolConfig.Default.maximumPoolSize)
      minimumIdle       <- integerEnv("PG_POOL_MIN_IDLE", math.min(PoolConfig.Default.minimumIdle, maximumPoolSize))
      connectionTimeout <- longEnv("PG_POOL_CONNECTION_TIMEOUT_MS", PoolConfig.Default.connectionTimeoutMillis)
      validationTimeout <- longEnv("PG_POOL_VALIDATION_TIMEOUT_MS", PoolConfig.Default.validationTimeoutMillis)
      idleTimeout       <- longEnv("PG_POOL_IDLE_TIMEOUT_MS", PoolConfig.Default.idleTimeoutMillis)
      maxLifetime       <- longEnv("PG_POOL_MAX_LIFETIME_MS", PoolConfig.Default.maxLifetimeMillis)
      keepalive         <- longEnv("PG_POOL_KEEPALIVE_TIME_MS", PoolConfig.Default.keepaliveTimeMillis)
      config            <- PoolConfig(
                             maximumPoolSize,
                             minimumIdle,
                             connectionTimeout,
                             validationTimeout,
                             idleTimeout,
                             maxLifetime,
                             keepalive,
                           ).validate
    yield config

  private def connectionConfigFromEnv(
    urlEnv: String,
    userEnv: String,
    passEnv: String,
  ): Either[String, (String, String, String, PoolConfig)] =
    for
      jdbcUrl  <- sys.env.get(urlEnv).map(_.trim).filter(_.nonEmpty).toRight(s"Missing env '$urlEnv'")
      username <- sys.env.get(userEnv).map(_.trim).filter(_.nonEmpty).toRight(s"Missing env '$userEnv'")
      password <- sys.env.get(passEnv).map(_.trim).filter(_.nonEmpty).toRight(s"Missing env '$passEnv'")
      pool     <- poolConfigFromEnvironment
    yield (jdbcUrl, username, password, pool)

  private def integerEnv(name: String, default: Int): Either[String, Int] =
    sys.env.get(name).map(_.trim).filter(_.nonEmpty) match
      case None        => Right(default)
      case Some(value) => value.toIntOption.toRight(s"$name must be a decimal integer")

  private def longEnv(name: String, default: Long): Either[String, Long] =
    sys.env.get(name).map(_.trim).filter(_.nonEmpty) match
      case None        => Right(default)
      case Some(value) => value.toLongOption.toRight(s"$name must be a decimal integer")

  private def close(dataSource: DataSource): ZIO[Any, Nothing, Unit] =
    dataSource match
      case pool: HikariDataSource => ResourceFinalizer.closeBlocking("PostgreSQL connection pool")(pool.close())
      case _                      => ZIO.unit

  def closeScoped(dataSource: DataSource): UIO[Unit] = close(dataSource)

  def poolStats(dataSource: DataSource): Option[PoolStats] = dataSource match
    case pool: HikariDataSource =>
      val runtime = pool.getHikariPoolMXBean
      Some(
        PoolStats(
          runtime.getTotalConnections,
          runtime.getActiveConnections,
          runtime.getIdleConnections,
          pool.getMaximumPoolSize,
          runtime.getThreadsAwaitingConnection,
        )
      )
    case _                      => None

  /**
   * Publish bounded-cardinality pool saturation gauges for the lifetime of
   * the surrounding scope. The data source is sampled, never wrapped, so this
   * does not enter the connection acquisition hot path.
   */
  def superviseMetrics(
    dataSource: DataSource,
    metrics: MetricsRegistry,
    poolName: String,
    interval: Duration = Duration.fromSeconds(10),
  ): ZIO[Scope, Nothing, Unit] =
    publishMetrics(dataSource, metrics, poolName)
      .repeat(Schedule.spaced(interval))
      .forkScoped
      .unit

  private[pg] def publishMetrics(
    dataSource: DataSource,
    metrics: MetricsRegistry,
    poolName: String,
  ): UIO[Unit] =
    poolStats(dataSource) match
      case None        => ZIO.unit
      case Some(stats) =>
        val base = Map("pool" -> poolName)
        metrics.gauge(MetricKeys.PostgresPoolConnections, stats.activeConnections.toDouble, base + ("state" -> "active")) *>
          metrics.gauge(MetricKeys.PostgresPoolConnections, stats.idleConnections.toDouble, base + ("state" -> "idle")) *>
          metrics.gauge(MetricKeys.PostgresPoolConnections, stats.totalConnections.toDouble, base + ("state" -> "total")) *>
          metrics.gauge(MetricKeys.PostgresPoolConnections, stats.maximumPoolSize.toDouble, base + ("state" -> "maximum")) *>
          metrics.gauge(MetricKeys.PostgresPoolAwaiting, stats.awaitingConnection.toDouble, base)

  private def build(jdbcUrl: String, username: String, password: String, pool: PoolConfig): DataSource =
    val config = new HikariConfig()
    config.setPoolName("graviton-postgres")
    config.setJdbcUrl(jdbcUrl)
    config.setUsername(username)
    config.setPassword(password)
    config.setMaximumPoolSize(pool.maximumPoolSize)
    config.setMinimumIdle(pool.minimumIdle)
    config.setConnectionTimeout(pool.connectionTimeoutMillis)
    config.setValidationTimeout(pool.validationTimeoutMillis)
    config.setIdleTimeout(pool.idleTimeoutMillis)
    config.setMaxLifetime(pool.maxLifetimeMillis)
    config.setKeepaliveTime(pool.keepaliveTimeMillis)
    config.setInitializationFailTimeout(1L)
    config.addDataSourceProperty("tcpKeepAlive", "true")
    new HikariDataSource(config)
