package graviton.runtime.upload

import zio.*

/** Storage-neutral session placement port. */
trait UploadPlacement:
  def localNode: UIO[UploadNode]

  def locate(key: UploadSessionKey): IO[UploadPlacement.Error, UploadNode]

  def assignments: IO[UploadPlacement.Error, Chunk[UploadShardAssignment]]

object UploadPlacement:
  sealed trait Error extends Throwable

  object Error:
    final case class NoOwner(key: UploadSessionKey) extends Error:
      override def getMessage: String = s"No upload node owns session ${key.uploadSessionId.value}"

    final case class BackendFailure(operation: String, cause: Throwable) extends Error:
      override def getMessage: String  =
        s"Upload placement $operation failed: ${Option(cause.getMessage).getOrElse(cause.getClass.getSimpleName)}"
      override def getCause: Throwable = cause

    final case class InvalidAssignment(reason: String) extends Error:
      override def getMessage: String = s"Invalid upload shard assignment: $reason"

  val service: ZIO[UploadPlacement, Nothing, UploadPlacement] = ZIO.service[UploadPlacement]

  def locate(key: UploadSessionKey): ZIO[UploadPlacement, Error, UploadNode] =
    ZIO.serviceWithZIO[UploadPlacement](_.locate(key))

  val localNode: ZIO[UploadPlacement, Nothing, UploadNode] =
    ZIO.serviceWithZIO[UploadPlacement](_.localNode)

  val assignments: ZIO[UploadPlacement, Error, Chunk[UploadShardAssignment]] =
    ZIO.serviceWithZIO[UploadPlacement](_.assignments)
