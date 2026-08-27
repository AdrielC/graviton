package graviton.runtime.upload

import zio.*
import zio.stream.ZStream

/** Streams an upload to a specific owner without replaying or collecting it. */
trait UploadNodeTransport:
  def upload(
    owner: UploadNode,
    key: UploadSessionKey,
    intent: UploadIntent,
    bytes: ZStream[Any, Throwable, Byte],
  ): IO[UploadNodeTransport.Error, LocalizedUploadResult]

object UploadNodeTransport:
  sealed trait Error extends Throwable

  object Error:
    final case class ConnectionFailure(owner: UploadNode, cause: Throwable) extends Error:
      override def getMessage: String  = s"Could not reach upload node ${owner.id.value}"
      override def getCause: Throwable = cause

    final case class Rejected(owner: UploadNode, status: Int, code: String) extends Error:
      override def getMessage: String = s"Upload node ${owner.id.value} rejected the stream with status $status ($code)"

    final case class InvalidResponse(owner: UploadNode, reason: String) extends Error:
      override def getMessage: String = s"Upload node ${owner.id.value} returned an invalid response: $reason"

  val service: ZIO[UploadNodeTransport, Nothing, UploadNodeTransport] = ZIO.service[UploadNodeTransport]

  def upload(
    owner: UploadNode,
    key: UploadSessionKey,
    intent: UploadIntent,
    bytes: ZStream[Any, Throwable, Byte],
  ): ZIO[UploadNodeTransport, Error, LocalizedUploadResult] =
    ZIO.serviceWithZIO[UploadNodeTransport](_.upload(owner, key, intent, bytes))

/** Performs the owner-local CAS ingest. Remote transports terminate here. */
trait UploadNodeIngest:
  def uploadLocal(
    key: UploadSessionKey,
    intent: UploadIntent,
    bytes: ZStream[Any, Throwable, Byte],
  ): IO[UploadNodeIngest.Error, LocalizedUploadResult]

object UploadNodeIngest:
  sealed trait Error extends Throwable

  object Error:
    final case class InvalidUpload(reason: String) extends Error:
      override def getMessage: String = reason

    final case class StorageFailure(cause: Throwable) extends Error:
      override def getMessage: String  = Option(cause.getMessage).getOrElse("Upload storage failed")
      override def getCause: Throwable = cause

  val service: ZIO[UploadNodeIngest, Nothing, UploadNodeIngest] = ZIO.service[UploadNodeIngest]

  def uploadLocal(
    key: UploadSessionKey,
    intent: UploadIntent,
    bytes: ZStream[Any, Throwable, Byte],
  ): ZIO[UploadNodeIngest, Error, LocalizedUploadResult] =
    ZIO.serviceWithZIO[UploadNodeIngest](_.uploadLocal(key, intent, bytes))
