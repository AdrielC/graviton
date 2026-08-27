package graviton.integration.shardcake

import com.devsisters.shardcake.ManagerConfig
import graviton.runtime.upload.UploadNodePort
import zio.*

final case class ShardcakeManagerSettings(
  apiPort: UploadNodePort,
  rebalanceInterval: Duration,
  rebalanceRetryInterval: Duration,
  pingTimeout: Duration,
  persistRetryInterval: Duration,
  persistRetryCount: Int,
  rebalanceRate: Double,
  podHealthCheckInterval: Duration,
):
  def validate: Either[String, ShardcakeManagerSettings] =
    for
      _ <- Either.cond(rebalanceInterval > Duration.Zero, (), "rebalanceInterval must be positive")
      _ <- Either.cond(rebalanceRetryInterval > Duration.Zero, (), "rebalanceRetryInterval must be positive")
      _ <- Either.cond(pingTimeout > Duration.Zero, (), "pingTimeout must be positive")
      _ <- Either.cond(persistRetryInterval > Duration.Zero, (), "persistRetryInterval must be positive")
      _ <- Either.cond(persistRetryCount >= 0 && persistRetryCount <= 10000, (), "persistRetryCount must be within 0..10000")
      _ <- Either.cond(rebalanceRate > 0.0 && rebalanceRate <= 1.0, (), "rebalanceRate must be within (0, 1]")
      _ <- Either.cond(podHealthCheckInterval > Duration.Zero, (), "podHealthCheckInterval must be positive")
    yield this

  def toUpstream(numberOfShards: Int): ManagerConfig =
    ManagerConfig(
      numberOfShards,
      apiPort.value,
      rebalanceInterval,
      rebalanceRetryInterval,
      pingTimeout,
      persistRetryInterval,
      persistRetryCount,
      rebalanceRate,
      podHealthCheckInterval,
    )

object ShardcakeManagerSettings:
  val Default: ShardcakeManagerSettings = ShardcakeManagerSettings(
    UploadNodePort.applyUnsafe(8080),
    20.seconds,
    10.seconds,
    3.seconds,
    3.seconds,
    100,
    0.02,
    1.minute,
  )

  val config: Config[ShardcakeManagerSettings] =
    (Config.int("api-port").withDefault(Default.apiPort.value) ++
      Config.duration("rebalance-interval").withDefault(Default.rebalanceInterval) ++
      Config.duration("rebalance-retry-interval").withDefault(Default.rebalanceRetryInterval) ++
      Config.duration("ping-timeout").withDefault(Default.pingTimeout) ++
      Config.duration("persist-retry-interval").withDefault(Default.persistRetryInterval) ++
      Config.int("persist-retry-count").withDefault(Default.persistRetryCount) ++
      Config.double("rebalance-rate").withDefault(Default.rebalanceRate) ++
      Config.duration("pod-health-check-interval").withDefault(Default.podHealthCheckInterval))
      .mapOrFail { case (portValue, rebalance, rebalanceRetry, ping, persist, retries, rate, health) =>
        for
          port    <- UploadNodePort.either(portValue).left.map(message => Config.Error.InvalidData(Chunk.empty, message))
          settings = ShardcakeManagerSettings(port, rebalance, rebalanceRetry, ping, persist, retries, rate, health)
          valid   <- settings.validate.left.map(message => Config.Error.InvalidData(Chunk.empty, message))
        yield valid
      }
      .nested("manager")
      .nested("shardcake")
      .nested("graviton")

  val layer: ZLayer[Any, Config.Error, ShardcakeManagerSettings] = ZLayer.fromZIO(ZIO.config(config))
