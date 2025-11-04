package graviton.runtime.upload

import zio.*
import zio.stream.*

/** Request payload describing a new upload session to be registered. */
final case class RegisterUploadRequest(
  name: Option[String],
  totalSize: Option[Long],
  mediaType: Option[String],
  metadata: Map[String, Map[String, String]],
  chunkSizeHint: Option[Int],
  expectedChecksums: Map[Int, String],
)

object RegisterUploadRequest:
  val empty: RegisterUploadRequest = RegisterUploadRequest(
    name = None,
    totalSize = None,
    mediaType = None,
    metadata = Map.empty,
    chunkSizeHint = None,
    expectedChecksums = Map.empty,
  )

/** Runtime representation of a server-side upload session. */
final case class UploadSession(
  uploadId: String,
  chunkSize: Int,
  maxChunks: Int,
  expiresAtEpochSeconds: Option[Long],
  metadata: Map[String, Map[String, String]],
)

/** Handle returned by [[UploadService]] scoped registration. */
trait UploadHandle:
  def session: UploadSession
  def sink: ZSink[Any, UploadError, FileChunk, Nothing, Unit]
  def uploadPart(part: FileChunk): IO[UploadError, Unit]
  def completeUpload(expectedParts: Chunk[UploadedPart]): IO[UploadError, DocumentId]
  def abort: UIO[Unit]

/** Typeclass for registering new upload sessions with resource safety. */
trait UploadService:
  def registerUpload(request: RegisterUploadRequest): ZIO[Scope, UploadError, UploadHandle]

/** Envelope carrying chunk bytes and associated offsets. */
final case class FileChunk(
  offset: Long,
  bytes: Chunk[Byte],
  checksum: Option[String],
)

object FileChunk:
  def fromBytes(offset: Long, bytes: Array[Byte]): FileChunk =
    FileChunk(offset, Chunk.fromArray(bytes), None)

/** Descriptor communicated when finalising an upload. */
final case class UploadedPart(
  partNumber: Int,
  offset: Long,
  size: Long,
  checksum: Option[String],
)

/** Identifier returned when an upload is fully materialised. */
opaque type DocumentId = String

object DocumentId:
  def apply(value: String): DocumentId         = value
  extension (id: DocumentId) def value: String = id

/** Typed failures surfaced during upload orchestration. */
sealed trait UploadError derives CanEqual:
  def message: String

object UploadError:
  final case class InvalidPart(message: String)                                       extends UploadError
  final case class UploadNotFound(uploadId: String)                                   extends UploadError:
    override def message: String = s"Upload $uploadId was not found"
  final case class UploadAlreadyCompleted(uploadId: String)                           extends UploadError:
    override def message: String = s"Upload $uploadId already completed"
  final case class StorageFailure(message: String)                                    extends UploadError
  final case class TransportFailure(message: String, cause: Option[Throwable] = None) extends UploadError
  final case class ProtocolViolation(message: String)                                 extends UploadError
  case object IncompleteUpload                                                        extends UploadError:
    override def message: String = "Upload did not receive all expected parts"
