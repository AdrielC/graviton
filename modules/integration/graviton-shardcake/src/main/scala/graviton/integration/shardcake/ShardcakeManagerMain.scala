package graviton.integration.shardcake

import com.devsisters.shardcake.interfaces.{PodsHealth, Storage}
import com.devsisters.shardcake.{GrpcPods, ManagerConfig, Server, ShardManager}
import zio.*
import zio.http.Middleware

object ShardcakeManagerMain extends ZIOAppDefault:
  sealed trait Error extends Exception

  object Error:
    case object Disabled extends Exception("GRAVITON_SHARDCAKE_ENABLED must be true") with Error

  override def run: ZIO[Any, Any, Any] =
    for
      uploadConfig <- ZIO.config(ShardcakeUploadConfig.config)
      settings     <- ZIO.config(ShardcakeManagerSettings.config)
      _            <- ZIO.fail(Error.Disabled).unless(uploadConfig.enabled)
      token        <- ZIO.fromOption(uploadConfig.internalToken).orElseFail(ShardcakeGrpcConfig.Error.MissingInternalToken)
      managerConfig = settings.toUpstream(uploadConfig.numberOfShards)
      auth          = Middleware.bearerAuthZIO(secret => ZIO.succeed(ShardcakeInternalAuth.matches(secret.stringValue, token)))
      _            <- (ZIO.service[ShardcakeManagerLease] *> Server.run(auth)).provide(
                        ZLayer.succeed(uploadConfig),
                        ZLayer.succeed(managerConfig),
                        ShardcakeDataSource.Config.layer,
                        ShardcakeDataSource.live,
                        ShardcakeManagerLease.live,
                        PgShardcakeStorage.Config.layer,
                        PgShardcakeStorage.live,
                        ShardcakeGrpcConfig.live,
                        GrpcPods.live,
                        PodsHealth.local,
                        ShardManager.live,
                      )
    yield ()
