package graviton.server

import graviton.integration.shardcake.ShardcakeUploadConfig
import graviton.runtime.config.{BlockPersistenceConfig, GravitonConfig, MaintenanceConfig}
import graviton.security.SecurityConfig
import graviton.server.console.ConsoleConfig
import zio.*

object ConfigCheckMain extends ZIOAppDefault:
  override def run: Task[Unit] =
    for
      config    <- ZIO.config(GravitonConfig.config)
      _         <- ZIO.config(MaintenanceConfig.config)
      _         <- ZIO.config(BlockPersistenceConfig.config)
      shardcake <- ZIO.config(ShardcakeUploadConfig.config)
      console   <- ZIO.config(ConsoleConfig.config)
      _         <- ZIO.config(RuntimeHealth.Config.config)
      security  <- ZIO.config(SecurityConfig.config)
      validated <- ConfigurationValidation.validate(config, shardcake, console, security)
      _         <- Console.printLine(validated.render)
    yield ()
