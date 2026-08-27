package graviton.integration.shardcake

import com.devsisters.shardcake.{Messenger, Sharding}
import graviton.runtime.upload.*
import zio.*

object ShardcakeUploadPlacement:
  val live: ZLayer[ShardcakeUploadConfig & Sharding, Nothing, UploadPlacement] =
    ZLayer.fromZIO {
      for
        config    <- ZIO.service[ShardcakeUploadConfig]
        sharding  <- ZIO.service[Sharding]
        messenger <- Sharding.messenger(UploadControlEntity)
      yield Live(config.node, messenger, sharding)
    }

  private final case class Live(
    node: UploadNode,
    messenger: Messenger[UploadControlMessage],
    sharding: Sharding,
  ) extends UploadPlacement:
    override val localNode: UIO[UploadNode] = ZIO.succeed(node)

    override def locate(key: UploadSessionKey): IO[UploadPlacement.Error, UploadNode] =
      messenger
        .send(key.entityId)(UploadControlMessage.Resolve.apply)
        .map { case UploadControlReply.Ready(owner, _) =>
          owner
        }
        .mapError(cause => UploadPlacement.Error.BackendFailure("locate", cause))

    override val assignments: IO[UploadPlacement.Error, Chunk[UploadShardAssignment]] =
      sharding.getAssignments.flatMap { values =>
        ZIO
          .foreach(values.toVector.sortBy(_._1)) { case (shardId, address) =>
            ZIO.fromEither {
              for
                host <- UploadNodeHost.either(address.host).left.map(UploadPlacement.Error.InvalidAssignment.apply)
                port <- UploadNodePort.either(address.port).left.map(UploadPlacement.Error.InvalidAssignment.apply)
              yield UploadShardAssignment(shardId, host, port)
            }
          }
          .map(Chunk.fromIterable)
      }
