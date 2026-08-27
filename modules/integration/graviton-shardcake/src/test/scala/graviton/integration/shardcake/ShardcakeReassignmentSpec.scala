package graviton.integration.shardcake

import com.devsisters.shardcake.interfaces.{PodsHealth, Storage}
import com.devsisters.shardcake.{
  Config,
  GrpcConfig,
  GrpcPods,
  GrpcShardingService,
  ManagerConfig,
  PodAddress,
  Server as ShardManagerServer,
  ShardManager,
  Sharding,
}
import graviton.runtime.upload.*
import zio.*
import zio.http.Middleware
import zio.test.*

import java.net.ServerSocket

object ShardcakeReassignmentSpec extends ZIOSpecDefault:
  private val token  = ShardcakeInternalToken.applyUnsafe("0123456789abcdefghijklmnopqrstuvwxyzABCDEFGH")
  private val tenant = TenantId.applyUnsafe("9f2f172c-8e6b-4aef-8be8-4c750420d971")

  private final case class NodeHandle(
    config: ShardcakeUploadConfig,
    placement: UploadPlacement,
  )

  private final case class LocatedSession(key: UploadSessionKey, owner: UploadNode)

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("Shardcake reassignment")(
      test("sessions distribute, stay sticky, and move after a node drains") {
        ZIO.scoped {
          for
            managerPort    <- freePort
            nodeAControl   <- freePort
            nodeAUpload    <- freePort
            nodeBControl   <- freePort
            nodeBUpload    <- freePort
            managerUri     <- ZIO.fromEither(ShardManagerEndpoint.parse(s"http://127.0.0.1:$managerPort/api/graphql"))
            managerConfig   = baseConfig(
                                UploadNode.fromEndpoints(
                                  UploadNodeHost.applyUnsafe("127.0.0.1"),
                                  UploadNodePort.applyUnsafe(nodeAControl),
                                  UploadNodePort.applyUnsafe(nodeAUpload),
                                ),
                                managerUri,
                              )
            nodeAConfig     = managerConfig
            nodeBConfig     = baseConfig(
                                UploadNode.fromEndpoints(
                                  UploadNodeHost.applyUnsafe("localhost"),
                                  UploadNodePort.applyUnsafe(nodeBControl),
                                  UploadNodePort.applyUnsafe(nodeBUpload),
                                ),
                                managerUri,
                              )
            storageEnv     <- Storage.memory.build
            storage         = storageEnv.get[Storage]
            grpc           <- grpcConfig(managerConfig)
            upstreamManager = ManagerConfig.default.copy(
                                numberOfShards = managerConfig.numberOfShards,
                                apiPort = managerPort,
                                rebalanceInterval = 1.second,
                                rebalanceRetryInterval = 200.millis,
                                pingTimeout = 2.seconds,
                                podHealthCheckInterval = 2.seconds,
                                rebalanceRate = 1.0,
                              )
            managerEnv     <- ZLayer
                                .make[ShardManager](
                                  ZLayer.succeed(storage),
                                  ZLayer.succeed(grpc),
                                  ZLayer.succeed(upstreamManager),
                                  GrpcPods.live,
                                  PodsHealth.local,
                                  ShardManager.live,
                                )
                                .build
            manager         = managerEnv.get[ShardManager]
            auth            = Middleware.bearerAuthZIO(secret => ZIO.succeed(ShardcakeInternalAuth.matches(secret.stringValue, token)))
            _              <- ShardManagerServer
                                .run(auth)
                                .provide(ZLayer.succeed(manager), ZLayer.succeed(upstreamManager))
                                .forkScoped
            _              <- ZIO.sleep(500.millis)
            nodeA          <- startNode(nodeAConfig, storage)
            movedKey       <- ZIO.scoped {
                                for
                                  nodeB      <- startNode(nodeBConfig, storage)
                                  _          <- awaitOwnerCount(manager, 2)
                                  candidates <-
                                    ZIO.foreach(1 to 512)(index =>
                                      sessionKey(index).flatMap(key => nodeA.placement.locate(key).map(owner => LocatedSession(key, owner)))
                                    )
                                  onB        <- ZIO
                                                  .fromOption(candidates.find(_.owner == nodeB.config.node).map(_.key))
                                                  .orElseFail(new IllegalStateException("no session was assigned to node B"))
                                  first      <- nodeA.placement.locate(onB)
                                  second     <- nodeA.placement.locate(onB)
                                  owners      = candidates.map(_.owner).toSet
                                  _          <- ZIO
                                                  .fail(new IllegalStateException("sessions did not distribute across both nodes"))
                                                  .unless(owners == Set(nodeA.config.node, nodeB.config.node))
                                  _          <- ZIO
                                                  .fail(new IllegalStateException("session ownership was not sticky"))
                                                  .unless(first == second && first == nodeB.config.node)
                                yield onB
                              }
            _              <- awaitOnlyOwner(manager, nodeA.config.node)
            reassigned     <- nodeA.placement.locate(movedKey)
            repeated       <- nodeA.placement.locate(movedKey)
          yield assertTrue(reassigned == nodeA.config.node, repeated == nodeA.config.node)
        }
      } @@ TestAspect.withLiveClock @@ TestAspect.timeout(45.seconds)
    ) @@ TestAspect.sequential

  private def baseConfig(
    node: UploadNode,
    manager: ShardManagerEndpoint,
  ): ShardcakeUploadConfig =
    ShardcakeUploadConfig.Default.copy(
      enabled = true,
      node = node,
      managerEndpoint = manager,
      numberOfShards = 64,
      serverVersion = "1.0.0",
      entityMaxIdleTime = 1.minute,
      sendTimeout = 5.seconds,
      internalToken = Some(token),
    )

  private def grpcConfig(config: ShardcakeUploadConfig): IO[ShardcakeGrpcConfig.Error, GrpcConfig] =
    ZIO.service[GrpcConfig].provide(ZLayer.succeed(config), ShardcakeGrpcConfig.live)

  private def startNode(
    config: ShardcakeUploadConfig,
    storage: Storage,
  ): ZIO[Scope, Throwable, NodeHandle] =
    for
      grpc         <- grpcConfig(config)
      upstream      = config.toShardcake
      shardingEnv  <- ZLayer
                        .make[Sharding](
                          ZLayer.succeed(config),
                          ZLayer.succeed(upstream),
                          ZLayer.succeed(grpc),
                          ZLayer.succeed(storage),
                          ZioBlocksShardcakeSerialization.layer,
                          AuthenticatedShardManagerClient.live,
                          GrpcPods.live,
                          Sharding.live,
                        )
                        .build
      sharding      = shardingEnv.get[Sharding]
      _            <- GrpcShardingService.live.build.provideSomeEnvironment[Scope](
                        _ ++ ZEnvironment(upstream, grpc, sharding)
                      )
      hotState     <- UploadHotState.inMemory(UploadHotState.Config(config.hotMaxSessions))
      _            <- UploadControlEntity
                        .register(config.node, config.entityMaxIdleTime)
                        .provideSomeEnvironment[Scope](_ ++ ZEnvironment(sharding, hotState))
      _            <- Sharding.registerScoped.provideSomeEnvironment[Scope](_ ++ ZEnvironment(sharding))
      placementEnv <- ShardcakeUploadPlacement.live.build.provideSomeEnvironment[Scope](
                        _ ++ ZEnvironment(config, sharding)
                      )
    yield NodeHandle(config, placementEnv.get[UploadPlacement])

  private def awaitOwnerCount(manager: ShardManager, count: Int): Task[Unit] =
    manager.getAssignments
      .map(_.values.flatten.toSet.size)
      .repeatUntil(_ == count)
      .timeoutFail(new IllegalStateException(s"timed out waiting for $count Shardcake owners"))(10.seconds)
      .unit

  private def awaitOnlyOwner(manager: ShardManager, node: UploadNode): Task[Unit] =
    val expected = Set(PodAddress(node.host.value, node.controlPort.value))
    manager.getAssignments
      .map(_.values.flatten.toSet)
      .repeatUntil(_ == expected)
      .timeoutFail(new IllegalStateException("timed out waiting for drained shards to be reassigned"))(10.seconds)
      .unit

  private def sessionKey(index: Int): UIO[UploadSessionKey] =
    val value = f"00000000-0000-4000-8000-$index%012x"
    ZIO
      .fromEither(UploadSessionId.either(value))
      .mapError(new IllegalArgumentException(_))
      .orDie
      .map(UploadSessionKey(tenant, _))

  private def freePort: Task[Int] =
    ZIO.acquireReleaseWith(ZIO.attemptBlocking(new ServerSocket(0)))(socket => ZIO.attemptBlocking(socket.close()).orDie)(socket =>
      ZIO.succeed(socket.getLocalPort)
    )
