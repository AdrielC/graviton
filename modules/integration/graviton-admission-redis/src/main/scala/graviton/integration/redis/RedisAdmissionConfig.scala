package graviton.integration.redis

import graviton.runtime.admission.{DistributedAdmissionLimits, DistributedBufferedBytes, DistributedTransferConcurrency}
import graviton.runtime.tenant.TenantCellId
import zio.{Chunk, Config, Duration, ZIO, ZLayer}
import zio.redis.RedisConfig as ZioRedisConfig

/** Validated Redis or Valkey connection and lease policy. */
final case class RedisAdmissionConfig(
  enabled: Boolean,
  cellId: TenantCellId,
  host: String,
  port: Int,
  tls: Boolean,
  verifyCertificate: Boolean,
  username: Option[String],
  password: Option[Config.Secret],
  requestQueueSize: Int,
  keyPrefix: String,
  limits: DistributedAdmissionLimits,
  leaseTtl: Duration,
  renewalInterval: Duration,
  acquisitionTimeout: Duration,
  retryInterval: Duration,
  maximumEvents: Long,
  maximumExpiredLeasesPerPass: Int,
):
  def validate: Either[String, RedisAdmissionConfig] =
    for
      _ <- Either.cond(host.nonEmpty, (), "redis host must be non-empty")
      _ <- Either.cond(port >= 1 && port <= 65535, (), "redis port must be within 1..65535")
      _ <-
        Either.cond(username.forall(value => value.nonEmpty && value.length <= 128), (), "redis username must be within 1..128 characters")
      _ <- Either.cond(
             password.forall(secret => secret.stringValue.nonEmpty && secret.stringValue.length <= 1024),
             (),
             "redis password must be within 1..1024 characters",
           )
      _ <- Either.cond(requestQueueSize >= 16 && requestQueueSize <= 65536, (), "redis request-queue-size must be within 16..65536")
      _ <- Either.cond(keyPrefix.nonEmpty && keyPrefix.length <= 64, (), "redis key-prefix must be within 1..64 characters")
      _ <-
        Either.cond(keyPrefix.forall(ch => ch.isLetterOrDigit || ch == '-' || ch == '_'), (), "redis key-prefix contains unsafe characters")
      _ <- limits.validate
      _ <- Either.cond(leaseTtl.toMillis >= 3000L, (), "redis lease-ttl must be at least 3 seconds")
      _ <- Either.cond(renewalInterval.toMillis >= 100L, (), "redis renewal-interval must be at least 100 milliseconds")
      _ <- Either.cond(
             renewalInterval.toMillis * 3L <= leaseTtl.toMillis,
             (),
             "redis renewal-interval must be no greater than one third of lease-ttl",
           )
      _ <- Either.cond(acquisitionTimeout.toMillis >= 1L, (), "redis acquisition-timeout must be positive")
      _ <- Either.cond(retryInterval.toMillis >= 1L, (), "redis retry-interval must be positive")
      _ <-
        Either.cond(retryInterval.toMillis <= acquisitionTimeout.toMillis, (), "redis retry-interval must not exceed acquisition-timeout")
      _ <- Either.cond(maximumEvents >= 1000L && maximumEvents <= 10000000L, (), "redis maximum-events must be within 1000..10000000")
      _ <- Either.cond(
             maximumExpiredLeasesPerPass >= 1 && maximumExpiredLeasesPerPass <= 4096,
             (),
             "redis maximum-expired-leases-per-pass must be within 1..4096",
           )
    yield this

  // zio-redis 1.2.1 renders Auth in RedisConfig.toString. Keep this conversion
  // provider-private and never attach the resulting value to logs or errors.
  private[redis] def redisConfig: ZioRedisConfig =
    ZioRedisConfig(
      host = host,
      port = port,
      sni = Option.when(tls)(host),
      ssl = tls,
      verifyCertificate = verifyCertificate,
      requestQueueSize = requestQueueSize,
      auth = password.map(secret => ZioRedisConfig.Auth(secret.stringValue, username)),
    )

object RedisAdmissionConfig:
  private final case class ConnectionSettings(
    enabled: Boolean,
    cell: String,
    host: String,
    port: Int,
    tls: Boolean,
    verify: Boolean,
    username: Option[String],
    password: Option[Config.Secret],
    queue: Int,
    prefix: String,
  )

  private final case class LeaseSettings(
    ttl: Duration,
    renewal: Duration,
    timeout: Duration,
    retry: Duration,
    events: Long,
    expired: Int,
  )

  val Default: RedisAdmissionConfig = RedisAdmissionConfig(
    enabled = false,
    cellId = TenantCellId.Default,
    host = "localhost",
    port = 6379,
    tls = false,
    verifyCertificate = true,
    username = None,
    password = None,
    requestQueueSize = 4096,
    keyPrefix = "graviton",
    limits = DistributedAdmissionLimits.Default,
    leaseTtl = Duration.fromSeconds(30),
    renewalInterval = Duration.fromSeconds(10),
    acquisitionTimeout = Duration.fromSeconds(10),
    retryInterval = Duration.fromMillis(50),
    maximumEvents = 100000L,
    maximumExpiredLeasesPerPass = 256,
  )

  private val connection =
    (Config.boolean("enabled").withDefault(Default.enabled) ++
      Config.string("cell-id").withDefault(Default.cellId.value) ++
      Config.string("host").withDefault(Default.host) ++
      Config.int("port").withDefault(Default.port) ++
      Config.boolean("tls").withDefault(Default.tls) ++
      Config.boolean("verify-certificate").withDefault(Default.verifyCertificate) ++
      Config.string("username").optional ++
      Config.secret("password").optional ++
      Config.int("request-queue-size").withDefault(Default.requestQueueSize) ++
      Config.string("key-prefix").withDefault(Default.keyPrefix)).map(ConnectionSettings.apply)

  private val limits =
    (Config.long("maximum-service-buffered-bytes").withDefault(Default.limits.maximumServiceBufferedBytes.value) ++
      Config.int("maximum-concurrent-service-transfers").withDefault(Default.limits.maximumConcurrentServiceTransfers.value) ++
      Config.long("maximum-tenant-buffered-bytes").withDefault(Default.limits.maximumTenantBufferedBytes.value) ++
      Config.int("maximum-concurrent-tenant-transfers").withDefault(Default.limits.maximumConcurrentTenantTransfers.value) ++
      Config.int("maximum-concurrent-backend-transfers").withDefault(Default.limits.maximumConcurrentBackendTransfers.value))
      .mapOrFail { case (serviceBytes, serviceTransfers, tenantBytes, tenantTransfers, backendTransfers) =>
        (for
          refinedServiceBytes     <- DistributedBufferedBytes.either(serviceBytes)
          refinedServiceTransfers <- DistributedTransferConcurrency.either(serviceTransfers)
          refinedTenantBytes      <- DistributedBufferedBytes.either(tenantBytes)
          refinedTenantTransfers  <- DistributedTransferConcurrency.either(tenantTransfers)
          refinedBackendTransfers <- DistributedTransferConcurrency.either(backendTransfers)
          value                   <- DistributedAdmissionLimits(
                                       refinedServiceBytes,
                                       refinedServiceTransfers,
                                       refinedTenantBytes,
                                       refinedTenantTransfers,
                                       refinedBackendTransfers,
                                     ).validate
        yield value).left.map(message => Config.Error.InvalidData(Chunk.empty, message))
      }

  private val leases =
    (Config.duration("lease-ttl").withDefault(Default.leaseTtl) ++
      Config.duration("renewal-interval").withDefault(Default.renewalInterval) ++
      Config.duration("acquisition-timeout").withDefault(Default.acquisitionTimeout) ++
      Config.duration("retry-interval").withDefault(Default.retryInterval) ++
      Config.long("maximum-events").withDefault(Default.maximumEvents) ++
      Config.int("maximum-expired-leases-per-pass").withDefault(Default.maximumExpiredLeasesPerPass)).map(LeaseSettings.apply)

  val config: Config[RedisAdmissionConfig] =
    (connection ++ limits ++ leases)
      .mapOrFail { case (connection, limits, leases) =>
        (for
          cellId <- TenantCellId.either(connection.cell)
          value  <- RedisAdmissionConfig(
                      connection.enabled,
                      cellId,
                      connection.host,
                      connection.port,
                      connection.tls,
                      connection.verify,
                      connection.username,
                      connection.password,
                      connection.queue,
                      connection.prefix,
                      limits,
                      leases.ttl,
                      leases.renewal,
                      leases.timeout,
                      leases.retry,
                      leases.events,
                      leases.expired,
                    ).validate
        yield value).left.map(message => Config.Error.InvalidData(Chunk.empty, message))
      }
      .nested("redis")
      .nested("distributed-admission")
      .nested("graviton")

  val layer: ZLayer[Any, Config.Error, RedisAdmissionConfig] = ZLayer.fromZIO(ZIO.config(config))
