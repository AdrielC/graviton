package graviton.integration.shardcake

import com.devsisters.shardcake.{EntityType, Sharding}
import graviton.runtime.upload.*
import zio.*

object UploadControlEntity extends EntityType[UploadControlMessage]("graviton-upload-session"):

  def register(
    localNode: UploadNode,
    maxIdleTime: Duration,
  ): ZIO[Sharding & UploadHotState & Scope, Nothing, Unit] =
    Sharding.registerEntity(
      UploadControlEntity,
      behavior(localNode),
      done => Some(UploadControlMessage.Terminate(done)),
      entityMaxIdleTime = Some(maxIdleTime),
    )

  private def behavior(
    localNode: UploadNode
  )(
    entityId: String,
    messages: Queue[UploadControlMessage],
  ): ZIO[Sharding & UploadHotState, Nothing, Nothing] =
    UploadSessionKey.parseEntityId(entityId) match
      case Left(reason) =>
        ZIO.logError(s"Rejected invalid upload entity ID: $reason") *> messages.take.forever
      case Right(key)   =>
        messages.take.flatMap {
          case UploadControlMessage.Resolve(reply)  =>
            UploadHotState.service.flatMap(_.snapshot(key)).flatMap(snapshot => reply.reply(UploadControlReply.Ready(localNode, snapshot)))
          case UploadControlMessage.Terminate(done) =>
            UploadHotState.service.flatMap(_.evict(key)) *> done.succeed(()) *> ZIO.interrupt
        }.forever
