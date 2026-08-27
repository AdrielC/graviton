package graviton.integration.shardcake

import com.devsisters.shardcake.Config as ShardcakeConfig
import graviton.core.RefinedTypeExt
import graviton.runtime.upload.*
import io.github.iltotore.iron.constraint.all.Match
import sttp.model.Uri
import sttp.client4.UriContext
import zio.*

final case class ShardManagerEndpoint private (uri: Uri)

object ShardManagerEndpoint:
  val LocalDefault: ShardManagerEndpoint = ShardManagerEndpoint(uri"http://localhost:8080/api/graphql")

  def parse(value: String): Either[String, ShardManagerEndpoint] =
    Uri.parse(value).left.map(_.toString).flatMap { uri =>
      Either.cond(
        uri.scheme.exists(scheme => scheme == "http" || scheme == "https") && uri.host.exists(_.nonEmpty),
        ShardManagerEndpoint(uri),
        "shard manager URI must be an absolute HTTP or HTTPS URI",
      )
    }

type ShardcakeInternalToken = ShardcakeInternalToken.T
object ShardcakeInternalToken extends RefinedTypeExt[String, Match["[A-Za-z0-9._~-]{32,256}"]]

final case class ShardcakeUploadConfig(
  enabled: Boolean,
  node: UploadNode,
  managerEndpoint: ShardManagerEndpoint,
  numberOfShards: Int,
  serverVersion: String,
  entityMaxIdleTime: Duration,
  entityTerminationTimeout: Duration,
  sendTimeout: Duration,
  refreshAssignmentsRetryInterval: Duration,
  unhealthyPodReportInterval: Duration,
  hotMaxSessions: Int,
  internalToken: Option[ShardcakeInternalToken],
):
  def validate: Either[String, ShardcakeUploadConfig] =
    for
      _ <- Either.cond(
             node.id == UploadNode.fromEndpoints(node.host, node.controlPort, node.uploadPort).id,
             (),
             "node ID must equal its canonical control host:port endpoint",
           )
      _ <- Either.cond(node.controlPort != node.uploadPort, (), "controlPort and uploadPort must be different")
      _ <- Either.cond(numberOfShards >= 16 && numberOfShards <= 65536, (), "numberOfShards must be within 16..65536")
      _ <- Either.cond(serverVersion.nonEmpty && serverVersion.length <= 64, (), "serverVersion must contain 1..64 characters")
      _ <- Either.cond(entityMaxIdleTime > Duration.Zero, (), "entityMaxIdleTime must be positive")
      _ <- Either.cond(entityTerminationTimeout > Duration.Zero, (), "entityTerminationTimeout must be positive")
      _ <- Either.cond(sendTimeout > Duration.Zero, (), "sendTimeout must be positive")
      _ <- Either.cond(refreshAssignmentsRetryInterval > Duration.Zero, (), "refreshAssignmentsRetryInterval must be positive")
      _ <- Either.cond(unhealthyPodReportInterval > Duration.Zero, (), "unhealthyPodReportInterval must be positive")
      _ <- Either.cond(hotMaxSessions >= 1 && hotMaxSessions <= 1000000, (), "hotMaxSessions must be within 1..1000000")
      _ <- Either.cond(!enabled || internalToken.isDefined, (), "internalToken is required when Shardcake is enabled")
    yield this

  def toShardcake: ShardcakeConfig =
    ShardcakeConfig(
      numberOfShards = numberOfShards,
      selfHost = node.host.value,
      shardingPort = node.controlPort.value,
      shardManagerUri = managerEndpoint.uri,
      serverVersion = serverVersion,
      entityMaxIdleTime = entityMaxIdleTime,
      entityTerminationTimeout = entityTerminationTimeout,
      sendTimeout = sendTimeout,
      refreshAssignmentsRetryInterval = refreshAssignmentsRetryInterval,
      unhealthyPodReportInterval = unhealthyPodReportInterval,
      simulateRemotePods = false,
      unregisterRetrySchedule = Schedule.spaced(3.seconds) && Schedule.recurs(5),
    )

object ShardcakeUploadConfig:
  private val DefaultManager     = ShardManagerEndpoint.LocalDefault
  private val DefaultHost        = UploadNodeHost.applyUnsafe("localhost")
  private val DefaultControlPort = UploadNodePort.applyUnsafe(54321)
  private val DefaultUploadPort  = UploadNodePort.applyUnsafe(54322)
  private val DefaultNode        = UploadNode.fromEndpoints(DefaultHost, DefaultControlPort, DefaultUploadPort)

  val Default: ShardcakeUploadConfig = ShardcakeUploadConfig(
    enabled = false,
    node = DefaultNode,
    managerEndpoint = DefaultManager,
    numberOfShards = 1024,
    serverVersion = "development",
    entityMaxIdleTime = 5.minutes,
    entityTerminationTimeout = 10.seconds,
    sendTimeout = 10.seconds,
    refreshAssignmentsRetryInterval = 5.seconds,
    unhealthyPodReportInterval = 5.seconds,
    hotMaxSessions = 4096,
    internalToken = None,
  )

  val config: Config[ShardcakeUploadConfig] =
    (Config.boolean("enabled").withDefault(Default.enabled) ++
      Config.string("host").withDefault(Default.node.host.value) ++
      Config.int("control-port").withDefault(Default.node.controlPort.value) ++
      Config.int("upload-port").withDefault(Default.node.uploadPort.value) ++
      Config.string("manager-uri").withDefault(Default.managerEndpoint.uri.toString) ++
      Config.int("number-of-shards").withDefault(Default.numberOfShards) ++
      Config.string("server-version").withDefault(Default.serverVersion) ++
      Config.duration("entity-max-idle-time").withDefault(Default.entityMaxIdleTime) ++
      Config.duration("entity-termination-timeout").withDefault(Default.entityTerminationTimeout) ++
      Config.duration("send-timeout").withDefault(Default.sendTimeout) ++
      Config.duration("refresh-assignments-retry-interval").withDefault(Default.refreshAssignmentsRetryInterval) ++
      Config.duration("unhealthy-pod-report-interval").withDefault(Default.unhealthyPodReportInterval) ++
      Config.int("hot-max-sessions").withDefault(Default.hotMaxSessions) ++
      Config.string("internal-token").optional)
      .mapOrFail {
        case (
              enabled,
              hostText,
              controlPortValue,
              uploadPortValue,
              managerText,
              shards,
              version,
              idle,
              termination,
              send,
              refresh,
              unhealthy,
              hot,
              tokenText,
            ) =>
          val parsed = for
            host        <- UploadNodeHost.either(hostText)
            controlPort <- UploadNodePort.either(controlPortValue)
            uploadPort  <- UploadNodePort.either(uploadPortValue)
            manager     <- ShardManagerEndpoint.parse(managerText)
            token       <- tokenText match
                             case None        => Right(None)
                             case Some(value) => ShardcakeInternalToken.either(value).map(Some(_))
            candidate    = ShardcakeUploadConfig(
                             enabled,
                             UploadNode.fromEndpoints(host, controlPort, uploadPort),
                             manager,
                             shards,
                             version,
                             idle,
                             termination,
                             send,
                             refresh,
                             unhealthy,
                             hot,
                             token,
                           )
            valid       <- candidate.validate
          yield valid
          parsed.left.map(message => Config.Error.InvalidData(Chunk.empty, message))
      }
      .nested("shardcake")
      .nested("graviton")

  val layer: ZLayer[Any, Config.Error, ShardcakeUploadConfig] =
    ZLayer.fromZIO(ZIO.config(config))

  val default: ULayer[ShardcakeUploadConfig] = ZLayer.succeed(Default)

  val upstream: ZLayer[ShardcakeUploadConfig, Nothing, ShardcakeConfig] =
    ZLayer.fromFunction((config: ShardcakeUploadConfig) => config.toShardcake)
