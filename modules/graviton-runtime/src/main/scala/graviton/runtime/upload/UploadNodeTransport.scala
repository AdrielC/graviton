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

  def uploadSource(
    owner: UploadNode,
    key: UploadSessionKey,
    intent: UploadIntent,
    source: UploadSource,
  ): IO[UploadNodeTransport.Error, LocalizedUploadResult] =
    upload(owner, key, intent, source.bytes.mapError(error => error: Throwable))

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

    final case class SourceFailure(owner: UploadNode, cause: UploadSourceError) extends Error:
      override def getMessage: String  = s"Upload source failed while streaming to node ${owner.id.value}"
      override def getCause: Throwable = cause

  val service: ZIO[UploadNodeTransport, Nothing, UploadNodeTransport] = ZIO.service[UploadNodeTransport]

  def upload(
    owner: UploadNode,
    key: UploadSessionKey,
    intent: UploadIntent,
    bytes: ZStream[Any, Throwable, Byte],
  ): ZIO[UploadNodeTransport, Error, LocalizedUploadResult] =
    ZIO.serviceWithZIO[UploadNodeTransport](_.upload(owner, key, intent, bytes))

  def uploadSource(
    owner: UploadNode,
    key: UploadSessionKey,
    intent: UploadIntent,
    source: UploadSource,
  ): ZIO[UploadNodeTransport, Error, LocalizedUploadResult] =
    ZIO.serviceWithZIO[UploadNodeTransport](_.uploadSource(owner, key, intent, source))

/** Performs the owner-local CAS ingest. Remote transports terminate here. */
trait UploadNodeIngest:
  def uploadLocal(
    key: UploadSessionKey,
    intent: UploadIntent,
    bytes: ZStream[Any, Throwable, Byte],
  ): IO[UploadNodeIngest.Error, LocalizedUploadResult]

  def uploadLocalSource(
    key: UploadSessionKey,
    intent: UploadIntent,
    source: UploadSource,
  ): IO[UploadNodeIngest.Error, LocalizedUploadResult] =
    uploadLocal(key, intent, source.bytes.mapError(error => error: Throwable))

object UploadNodeIngest:
  sealed trait Error extends Throwable

  object Error:
    final case class InvalidUpload(reason: String) extends Error:
      override def getMessage: String = reason

    final case class StorageFailure(cause: Throwable) extends Error:
      override def getMessage: String  = Option(cause.getMessage).getOrElse("Upload storage failed")
      override def getCause: Throwable = cause

    final case class SourceFailure(cause: UploadSourceError) extends Error:
      override def getMessage: String  = "Upload source failed"
      override def getCause: Throwable = cause

  val service: ZIO[UploadNodeIngest, Nothing, UploadNodeIngest] = ZIO.service[UploadNodeIngest]

  def uploadLocal(
    key: UploadSessionKey,
    intent: UploadIntent,
    bytes: ZStream[Any, Throwable, Byte],
  ): ZIO[UploadNodeIngest, Error, LocalizedUploadResult] =
    ZIO.serviceWithZIO[UploadNodeIngest](_.uploadLocal(key, intent, bytes))

  def uploadLocalSource(
    key: UploadSessionKey,
    intent: UploadIntent,
    source: UploadSource,
  ): ZIO[UploadNodeIngest, Error, LocalizedUploadResult] =
    ZIO.serviceWithZIO[UploadNodeIngest](_.uploadLocalSource(key, intent, source))
