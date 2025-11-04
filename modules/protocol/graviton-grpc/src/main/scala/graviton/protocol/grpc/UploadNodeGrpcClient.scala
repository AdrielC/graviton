package graviton.protocol.grpc

import UploadNodeGrpcClient.*
import graviton.runtime.upload.{RegisterUploadRequest as DomainRegisterUploadRequest, UploadedPart}
import io.grpc.Status
import zio.*
import zio.stream.*

final class UploadNodeGrpcClient(
  service: UploadServiceClient,
  defaultChunkSize: Int = 4 * 1024 * 1024,
):

  def registerUpload(request: DomainRegisterUploadRequest): IO[UploadNodeGrpcError, RegisterUploadResponse] =
    service
      .registerUpload(RegisterUploadRequest.fromDomain(request))
      .mapError(UploadNodeGrpcError.RpcFailure.apply)

  def uploadChunks(
    uploadId: String,
    data: ZStream[Any, Throwable, Byte],
    chunkSize: Option[Int],
    partChecksums: Map[Int, String],
  ): IO[UploadNodeGrpcError, UploadProgress] =
    val size                                        = chunkSize.filter(_ > 0).getOrElse(defaultChunkSize)
    val outbound: ZStream[Any, Status, UploadChunk] =
      chunkStream(uploadId, size, data, partChecksums).mapError(_.toStatus)

    service
      .uploadParts(outbound)
      .mapError(UploadNodeGrpcError.RpcFailure.apply)
      .runFoldZIO(UploadProgress.empty(uploadId)) { (progress, ack) =>
        if ack.uploadId != uploadId then
          ZIO.fail(UploadNodeGrpcError.ProtocolViolation(s"Ack for ${ack.uploadId} does not match $uploadId"))
        else if ack.acknowledgedSequence <= progress.sequence then
          ZIO.fail(
            UploadNodeGrpcError.ProtocolViolation(
              s"Upload ack sequence ${ack.acknowledgedSequence} not greater than previous ${progress.sequence}"
            )
          )
        else ZIO.succeed(progress.advance(ack.acknowledgedSequence, ack.receivedBytes))
      }

  def completeUpload(
    uploadId: String,
    parts: Chunk[UploadedPart],
    expectedChecksum: Option[String],
  ): IO[UploadNodeGrpcError, CompleteUploadResponse] =
    service
      .completeUpload(CompleteUploadRequest.fromDomain(uploadId, parts, expectedChecksum))
      .mapError(UploadNodeGrpcError.RpcFailure.apply)

  def uploadOneShot(
    request: DomainRegisterUploadRequest,
    data: ZStream[Any, Throwable, Byte],
    partChecksums: Map[Int, String],
    expectedChecksum: Option[String],
  ): IO[UploadNodeGrpcError, CompleteUploadResponse] =
    for {
      registered <- registerUpload(request)
      _          <- uploadChunks(registered.uploadId, data, Some(registered.chunkSize), partChecksums)
      parts       = Chunk.fromIterable(partChecksums.toList.sortBy(_._1).map { case (partNumber, checksum) =>
                      UploadedPart(
                        partNumber,
                        offset = (partNumber - 1L) * registered.chunkSize,
                        size = registered.chunkSize,
                        checksum = Some(checksum),
                      )
                    })
      completion <- completeUpload(registered.uploadId, parts, expectedChecksum)
    } yield completion

  private def chunkStream(
    uploadId: String,
    chunkSize: Int,
    data: ZStream[Any, Throwable, Byte],
    checksums: Map[Int, String],
  ): ZStream[Any, UploadNodeGrpcError, UploadChunk] =
    data
      .chunkN(chunkSize, allowFewer = true)
      .zipWithIndex
      .mapBoth(
        error => UploadNodeGrpcError.StreamFailure(error),
        { case (chunk, index) =>
          val sequence = index.toInt + 1
          val offset   = index.toLong * chunkSize.toLong
          val isLast   = chunk.size < chunkSize
          UploadChunk(
            uploadId = uploadId,
            sequenceNumber = sequence,
            offset = offset,
            data = chunk,
            checksum = checksums.get(sequence),
            last = isLast,
          )
        },
      )

object UploadNodeGrpcClient:

  trait UploadServiceClient:
    def registerUpload(request: RegisterUploadRequest): IO[Status, RegisterUploadResponse]
    def uploadParts(request: ZStream[Any, Status, UploadChunk]): ZStream[Any, Status, UploadAck]
    def completeUpload(request: CompleteUploadRequest): IO[Status, CompleteUploadResponse]

  final case class RegisterUploadRequest(
    fileName: Option[String],
    fileSize: Option[Long],
    mediaType: Option[String],
    metadata: Map[String, Map[String, String]],
    preferredChunkSize: Option[Int],
  )

  object RegisterUploadRequest:
    def fromDomain(request: DomainRegisterUploadRequest): RegisterUploadRequest =
      RegisterUploadRequest(
        fileName = request.name,
        fileSize = request.totalSize,
        mediaType = request.mediaType,
        metadata = request.metadata,
        preferredChunkSize = request.chunkSizeHint,
      )

  final case class RegisterUploadResponse(
    uploadId: String,
    chunkSize: Int,
    maxChunks: Int,
    expiresAtEpochSeconds: Option[Long],
  )

  final case class UploadChunk(
    uploadId: String,
    sequenceNumber: Int,
    offset: Long,
    data: Chunk[Byte],
    checksum: Option[String],
    last: Boolean,
  )

  final case class UploadAck(
    uploadId: String,
    acknowledgedSequence: Int,
    receivedBytes: Long,
  )

  final case class CompleteUploadRequest(
    uploadId: String,
    parts: List[CompletePart],
    expectedChecksum: Option[String],
  )

  object CompleteUploadRequest:
    def fromDomain(
      uploadId: String,
      parts: Chunk[UploadedPart],
      expectedChecksum: Option[String],
    ): CompleteUploadRequest =
      CompleteUploadRequest(
        uploadId = uploadId,
        parts = parts.map(CompletePart.fromDomain).toList,
        expectedChecksum = expectedChecksum,
      )

  final case class CompletePart(
    partNumber: Int,
    offset: Long,
    size: Long,
    checksum: Option[String],
  )

  object CompletePart:
    def fromDomain(part: UploadedPart): CompletePart =
      CompletePart(part.partNumber, part.offset, part.size, part.checksum)

  final case class CompleteUploadResponse(
    documentId: String,
    attributes: Map[String, String],
  )

  final case class UploadProgress(uploadId: String, bytes: Long, sequence: Int)

  object UploadProgress:
    def empty(uploadId: String): UploadProgress               = UploadProgress(uploadId, 0L, 0)
    extension (progress: UploadProgress)
      def advance(sequence: Int, bytes: Long): UploadProgress =
        progress.copy(bytes = bytes, sequence = sequence)

sealed trait UploadNodeGrpcError extends Product with Serializable:
  def message: String
  def toStatus: Status

object UploadNodeGrpcError:
  final case class RpcFailure(status: Status)         extends UploadNodeGrpcError:
    override def message: String  = status.getDescription
    override def toStatus: Status = status
  final case class StreamFailure(cause: Throwable)    extends UploadNodeGrpcError:
    override def message: String  = Option(cause.getMessage).getOrElse("stream failure")
    override def toStatus: Status = Status.INTERNAL.withDescription(message).withCause(cause)
  final case class ProtocolViolation(message: String) extends UploadNodeGrpcError:
    override def toStatus: Status = Status.INTERNAL.withDescription(message)
