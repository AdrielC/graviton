package graviton.integration.shardcake

import com.devsisters.shardcake.Replier
import graviton.runtime.upload.{UploadHotState, UploadNode}
import zio.Promise

sealed trait UploadControlMessage

object UploadControlMessage:
  final case class Resolve(reply: Replier[UploadControlReply]) extends UploadControlMessage

  /** Local-only lifecycle message installed as the Shardcake termination hook. */
  private[shardcake] final case class Terminate(done: Promise[Nothing, Unit]) extends UploadControlMessage

sealed trait UploadControlReply

object UploadControlReply:
  final case class Ready(
    owner: UploadNode,
    hotState: Option[UploadHotState.Snapshot],
  ) extends UploadControlReply
