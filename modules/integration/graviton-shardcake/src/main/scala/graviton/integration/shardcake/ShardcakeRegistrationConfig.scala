package graviton.integration.shardcake

import zio.*

final case class ShardcakeRegistrationConfig(
  retryInterval: Duration,
  timeout: Duration,
):
  def validate: Either[String, ShardcakeRegistrationConfig] =
    for
      _ <- Either.cond(retryInterval > Duration.Zero, (), "registrationRetryInterval must be positive")
      _ <- Either.cond(timeout > Duration.Zero, (), "registrationTimeout must be positive")
      _ <- Either.cond(timeout >= retryInterval, (), "registrationTimeout must be at least registrationRetryInterval")
    yield this

object ShardcakeRegistrationConfig:
  val Default: ShardcakeRegistrationConfig = ShardcakeRegistrationConfig(
    retryInterval = 500.millis,
    timeout = 30.seconds,
  )

  val config: Config[ShardcakeRegistrationConfig] =
    (Config.duration("registration-retry-interval").withDefault(Default.retryInterval) ++
      Config.duration("registration-timeout").withDefault(Default.timeout))
      .map { case (retryInterval, timeout) => ShardcakeRegistrationConfig(retryInterval, timeout) }
      .mapOrFail(candidate => candidate.validate.left.map(message => Config.Error.InvalidData(Chunk.empty, message)))
      .nested("shardcake")
      .nested("graviton")

  val layer: ZLayer[Any, Config.Error, ShardcakeRegistrationConfig] =
    ZLayer.fromZIO(ZIO.config(config))
