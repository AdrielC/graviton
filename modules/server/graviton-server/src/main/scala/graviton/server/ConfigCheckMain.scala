package graviton.server

import graviton.backend.pg.PgDataSource
import graviton.integration.shardcake.{ShardcakeDataSource, ShardcakeRegistrationConfig, ShardcakeUploadConfig}
import graviton.runtime.config.{BlockPersistenceConfig, GravitonConfig, MaintenanceConfig, TenantDataPlaneConfig, TenantStorageConfig}
import graviton.security.SecurityConfig
import graviton.server.console.ConsoleConfig
import zio.*

object ConfigCheckMain extends ZIOAppDefault:
  override def run: Task[Unit] =
    for
      config       <- ZIO.config(GravitonConfig.config)
      _            <- ZIO.config(MaintenanceConfig.config)
      _            <- ZIO.config(BlockPersistenceConfig.config)
      shardcake    <- ZIO.config(ShardcakeUploadConfig.config)
      registration <- ZIO.config(ShardcakeRegistrationConfig.config)
      console      <- ZIO.config(ConsoleConfig.config)
      _            <- ZIO.config(RuntimeHealth.Config.config)
      security     <- ZIO.config(SecurityConfig.config)
      tenant       <- ZIO.config(TenantDataPlaneConfig.config)
      _            <- ZIO.config(TenantStorageConfig.config)
      _            <- ZIO.when(shardcake.enabled)(ZIO.config(ShardcakeDataSource.Config.config))
      validated    <- ConfigurationValidation.validate(config, shardcake, registration, console, security)
      _            <- Main.validateTenantDataPlane(config, tenant, security)
      _            <- ZIO
                        .fromEither(PgDataSource.validatePoolEnvironment)
                        .mapError(message => new IllegalArgumentException(s"invalid PostgreSQL pool configuration: $message"))
      _            <- Console.printLine(validated.render)
    yield ()
