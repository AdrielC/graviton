package graviton.runtime.config

import graviton.core.types.RepositoryNamespace
import zio.*
import zio.Config

/**
 * Cross-process repository coordination settings.
 *
 * The namespace must be identical for every process that can reach the same
 * manifest and block stores. Acquisition is polled rather than delegated to an
 * unbounded blocking call, so interruption and the configured timeout remain
 * observable even while another process owns maintenance.
 */
final case class MaintenanceConfig(
  namespace: RepositoryNamespace = MaintenanceConfig.DefaultNamespace,
  acquisitionTimeout: Duration = MaintenanceConfig.DefaultAcquisitionTimeout,
  pollInterval: Duration = MaintenanceConfig.DefaultPollInterval,
):

  def validate: Either[String, MaintenanceConfig] =
    for
      _ <- Either.cond(acquisitionTimeout > Duration.Zero, (), "acquisitionTimeout must be positive")
      _ <- Either.cond(pollInterval > Duration.Zero, (), "pollInterval must be positive")
      _ <- Either.cond(
             pollInterval <= acquisitionTimeout,
             (),
             "pollInterval must not exceed acquisitionTimeout",
           )
    yield this

object MaintenanceConfig:
  val DefaultNamespace: RepositoryNamespace = RepositoryNamespace.applyUnsafe("graviton")
  val DefaultAcquisitionTimeout: Duration   = 30.seconds
  val DefaultPollInterval: Duration         = 100.millis

  val Default: MaintenanceConfig = MaintenanceConfig()

  /** ZIO Config descriptor rooted at `GRAVITON_MAINTENANCE_*`. */
  val config: Config[MaintenanceConfig] =
    (Config.string("namespace").withDefault(DefaultNamespace.value) ++
      Config.duration("acquisition-timeout").withDefault(DefaultAcquisitionTimeout) ++
      Config.duration("poll-interval").withDefault(DefaultPollInterval))
      .mapOrFail { case (namespace, timeout, poll) =>
        RepositoryNamespace
          .either(namespace)
          .left
          .map(message => Config.Error.InvalidData(Chunk.empty, message))
          .flatMap(value =>
            MaintenanceConfig(value, timeout, poll).validate.left
              .map(message => Config.Error.InvalidData(Chunk.empty, message))
          )
      }
      .nested("maintenance")
      .nested("graviton")

  val layer: ZLayer[Any, Config.Error, MaintenanceConfig] =
    ZLayer.fromZIO(ZIO.config(config))

  val default: ULayer[MaintenanceConfig] =
    ZLayer.succeed(Default)
