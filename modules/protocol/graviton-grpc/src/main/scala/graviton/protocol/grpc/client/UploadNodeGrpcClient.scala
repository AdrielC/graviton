package graviton.protocol.grpc.client

import zio.*
import zio.Chunk
import zio.stream.ZStream
import io.grpc.Status

import java.util.UUID

/**
 * High-level gRPC client for orchestrating uploads against remote upload nodes.
 *
 * The implementation is intentionally decoupled from the generated service stub
 * to keep the high level protocol testable without a live gRPC channel. The
 * actual stub is provided through [[UploadServiceClient]] which should be
 * backed by code generated via zio-grpc or a hand-written adapter in tests.
 */
trait UploadNodeGrpcClient {

  import UploadNodeGrpcClient.*

  /**
   * Stream a blob to the remote upload service.
   *
   * @param data
   *   byte stream representing the blob contents
   * @param expectedSize
   *   optional total byte size used for flow control credit accounting
   * @param expectedHash
   *   expected blob hash communicated to the server on completion for
   *   validation. The hashing is performed by the caller while producing the
   *   data stream.
   * @param attributes
   *   attributes persisted alongside the blob manifest
   * @param chunkSize
   *   chunk size boundary. Defaults to 1 MiB.
   */
  def uploadStream(
    data: ZStream[Any, Throwable, Byte],
    expectedSize: Option[Long] = None,
    expectedHash: Option[String] = None,
    attributes: Map[String, String] = Map.empty,
    chunkSize: Int = 1024 * 1024,
  ): IO[Status, UploadResult]
}

object UploadNodeGrpcClient:

  /** Algebra accepted by the underlying upload service stub. */
  trait UploadServiceClient {
    def upload(messages: ZStream[Any, Status, OutboundMessage]): ZStream[Any, Status, UploadAck]
  }

  /** Client level construction helpers. */
  def fromService(service: UploadServiceClient): UploadNodeGrpcClient = UploadNodeGrpcClientLive(service)

  def layer: ZLayer[UploadServiceClient, Nothing, UploadNodeGrpcClient] =
    ZLayer.fromFunction(fromService)

  /** Client message envelope propagated over gRPC. */
  enum OutboundMessage {
    case Start(start: StartUpload)
    case Chunk(chunk: UploadChunk)
    case Complete(complete: CompleteUpload)
  }

  /** Start message transmitted before the first chunk. */
  final case class StartUpload(
    uploadId: String,
    totalSize: Option[Long],
    attributes: Map[String, String],
  )

  /** Individual chunk payload with byte offset for ordering validation. */
  final case class UploadChunk(
    bytes: Chunk[Byte],
    offset: Long,
  )

  /** Completion message emitted after the final chunk. */
  final case class CompleteUpload(
    expectedHash: Option[String]
  )

  /** Acknowledgement emitted by the server. */
  final case class UploadAck(
    uploadId: String,
    receivedBytes: Long,
  )

  /** Upload result summarising server side completion. */
  final case class UploadResult(
    uploadId: String,
    receivedBytes: Long,
  )

  private final case class UploadProgress(uploadId: String, received: Long)

  private final case class UploadNodeGrpcClientLive(service: UploadServiceClient) extends UploadNodeGrpcClient {
    override def uploadStream(
      data: ZStream[Any, Throwable, Byte],
      expectedSize: Option[Long],
      expectedHash: Option[String],
      attributes: Map[String, String],
      chunkSize: Int,
    ): IO[Status, UploadResult] =
      for {
        uploadId <- ZIO.succeed(UUID.randomUUID().toString)
        startMsg  = OutboundMessage.Start(StartUpload(uploadId, expectedSize, attributes))
        chunks    = chunkStream(data, chunkSize)
        complete  = ZStream.succeed(OutboundMessage.Complete(CompleteUpload(expectedHash)))
        outbound  = ZStream.succeed(startMsg) ++ chunks ++ complete
        result   <- service
                      .upload(outbound)
                      .runFold(UploadProgress(uploadId, 0L)) { case (_, ack) =>
                        UploadProgress(ack.uploadId, ack.receivedBytes)
                      }
                      .map(acc => UploadResult(acc.uploadId, acc.received))
      } yield result

    private def chunkStream(
      data: ZStream[Any, Throwable, Byte],
      chunkSize: Int,
    ): ZStream[Any, Status, OutboundMessage] =
      data
        .rechunk(chunkSize)
        .chunks
        .zipWithIndex
        .map { case (chunk, index) =>
          val offset = index * chunkSize.toLong
          OutboundMessage.Chunk(UploadChunk(chunk, offset))
        }
        .mapError(e => Status.fromThrowable(e))
  }
