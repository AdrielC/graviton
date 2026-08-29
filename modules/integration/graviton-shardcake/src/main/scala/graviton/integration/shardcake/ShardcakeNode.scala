package graviton.integration.shardcake

import com.devsisters.shardcake.interfaces.{Pods, Serialization, Storage}
import com.devsisters.shardcake.{Config, GrpcConfig, GrpcPods, GrpcShardingService, PodAddress, ShardManagerClient, Sharding}
import graviton.pdf.PdfUploadSupport
import graviton.runtime.stores.BlobStore
import graviton.runtime.metrics.MetricsRegistry
import graviton.runtime.upload.*
import zio.*
import zio.http.{Client, Server}

final case class ShardcakeNode(
  locality: LocalityAwareUpload,
  placement: UploadPlacement,
  hotState: UploadHotState,
  health: ShardcakeHealth,
):
  def healthCheck: IO[ShardcakeNode.HealthError, Unit] =
    health.readiness

object ShardcakeNode:
  sealed trait HealthError extends Throwable

  object HealthError:
    final case class PlacementUnavailable(cause: UploadPlacement.Error) extends HealthError:
      override def getMessage: String  = s"Shardcake placement is unavailable: ${cause.getMessage}"
      override def getCause: Throwable = cause

    final case class LocalNodeUnassigned(node: UploadNode) extends HealthError:
      override def getMessage: String = s"Shardcake upload node ${node.id.value} has no assigned shards"

  private[shardcake] def verifyAssigned(placement: UploadPlacement): IO[HealthError, Unit] =
    for
      local       <- placement.localNode
      assignments <- placement.assignments.mapError(HealthError.PlacementUnavailable.apply)
      assigned     = assignments.exists(assignment => assignment.controlHost == local.host && assignment.controlPort == local.controlPort)
      _           <- ZIO.fail(HealthError.LocalNodeUnassigned(local)).unless(assigned)
    yield ()

  private sealed trait ControlServer
  private case object ControlServer extends ControlServer

  private val controlServer: ZLayer[Config & GrpcConfig & Sharding, Throwable, ControlServer] =
    GrpcShardingService.live >>> ZLayer.succeed(ControlServer)

  private val configuredHotState: ZLayer[ShardcakeUploadConfig, Nothing, UploadHotState] =
    ZLayer.fromZIO {
      ZIO.service[ShardcakeUploadConfig].flatMap(config => UploadHotState.inMemory(UploadHotState.Config(config.hotMaxSessions)))
    }

  private val services: ZLayer[
    BlobStore & ShardcakeUploadConfig & MetricsRegistry,
    Throwable,
    ShardcakeNode,
  ] =
    ZLayer.makeSome[BlobStore & ShardcakeUploadConfig & MetricsRegistry, ShardcakeNode](
      ShardcakeUploadConfig.upstream,
      ShardcakeRegistrationConfig.layer,
      ShardcakeGrpcConfig.live,
      ZioBlocksShardcakeSerialization.layer,
      ShardcakeDataSource.Config.layer,
      ShardcakeDataSource.live,
      PgShardcakeStorage.Config.layer,
      PgShardcakeStorage.live,
      AuthenticatedShardManagerClient.live,
      GrpcPods.live,
      Sharding.live,
      controlServer,
      Client.default,
      UploadSessionContext.live,
      configuredHotState,
      PdfUploadSupport.layer(),
      CasUploadNodeIngest.live,
      ZioHttpUploadNodeTransport.live,
      ShardcakeUploadPlacement.live,
      ShardcakeHealth.live,
      LocalityAwareUpload.instrumented,
      ZLayer.scoped {
        for
          config             <- ZIO.service[ShardcakeUploadConfig]
          registrationConfig <- ZIO.service[ShardcakeRegistrationConfig]
          token              <- ZIO.fromOption(config.internalToken).orElseFail(ShardcakeGrpcConfig.Error.MissingInternalToken)
          _                  <- ZIO.service[ControlServer]
          sharding           <- ZIO.service[Sharding]
          storage            <- ZIO.service[Storage]
          placement          <- ZIO.service[UploadPlacement]
          ingest             <- ZIO.service[UploadNodeIngest]
          locality           <- ZIO.service[LocalityAwareUpload]
          hotState           <- ZIO.service[UploadHotState]
          health             <- ZIO.service[ShardcakeHealth]
          _                  <- UploadControlEntity.register(config.node, config.entityMaxIdleTime)
          address             = PodAddress(config.node.host.value, config.node.controlPort.value)
          _                  <- ShardcakeRegistration.scoped(
                                  sharding.register,
                                  storage.getPods.map(_.get(address).exists(_.version == config.serverVersion)),
                                  sharding.unregister,
                                  registrationConfig.retryInterval,
                                  registrationConfig.timeout,
                                )
          routes              = UploadNodeHttpApi(token, ingest).routes
          _                  <- Server
                                  .serve(routes)
                                  .provide(Server.defaultWith(_.port(config.node.uploadPort.value).enableRequestStreaming))
                                  .forkScoped
          _                  <- ZIO.logInfo(
                                  s"Shardcake upload node ${config.node.id.value} listening for streamed uploads on :${config.node.uploadPort.value}"
                                )
        yield ShardcakeNode(locality, placement, hotState, health)
      },
    )

  val live: ZLayer[BlobStore & ShardcakeUploadConfig & MetricsRegistry, Throwable, ShardcakeNode] = services
