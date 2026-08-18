package graviton.protocol.http

import zio.*
import zio.http.*
import zio.json.*
import zio.stream.ZStream

import java.time.Instant
import java.time.format.DateTimeFormatter
import scala.util.Try

/**
 * High level HTTP client for talking to upload nodes.
 *
 * The client wraps the multipart lifecycle exposed by the node and provides
 * helpers for single-shot uploads backed by streamed request bodies. All
 * methods are effectful and express failures as [[UploadNodeHttpClient.Error]]
 * to keep transport concerns separate from application failures.
 */
final case class UploadNodeHttpClient private (
  client: Client,
  baseUrl: URL,
  defaultHeaders: Headers,
):

  import UploadNodeHttpClient.*

  private val uploadsPath = baseUrl / "api" / "v1" / "uploads"
  private val blobsPath   = baseUrl / "api" / "v1" / "blobs"

  /** Start a multipart session. */
  def startMultipart(request: MultipartStartRequest): IO[Error, MultipartSession] =
    for {
      response <- execute(Method.POST, uploadsPath, Body.fromString(request.toJson), jsonHeaders)
      bodyText <- response.body.asString.mapError(Error.TransportFailure.apply)
      session  <- ZIO
                    .fromEither(bodyText.fromJson[MultipartSession])
                    .mapError(err => Error.DecodingFailed(response.status, bodyText, err))
    } yield session

  /** Upload an individual part inside an existing multipart session. */
  def uploadPart(
    session: MultipartSession,
    request: MultipartPartRequest,
    bytes: ZStream[Any, Throwable, Byte],
  ): IO[Error, PartAck] =
    val target       = uploadsPath / session.uploadId / "parts" / request.partNumber.toString
    val lengthHeader = request.contentLength.map(len => Header.Custom("Content-Length", len.toString))
    val partHeaders  = defaultHeaders ++ Headers(lengthHeader.toList*)

    for {
      payload  <- bytes.runCollect.mapError(Error.TransportFailure.apply)
      response <- execute(Method.PUT, target, Body.fromChunk(payload), partHeaders)
      bodyText <- response.body.asString.mapError(Error.TransportFailure.apply)
      ack      <- ZIO
                    .fromEither(bodyText.fromJson[PartAck])
                    .mapError(err => Error.DecodingFailed(response.status, bodyText, err))
    } yield ack

  /** Complete a multipart upload by supplying the collected part metadata. */
  def completeMultipart(session: MultipartSession, request: MultipartCompleteRequest): IO[Error, CompletedBlob] =
    for {
      response <- execute(Method.POST, uploadsPath / session.uploadId / "complete", Body.fromString(request.toJson), jsonHeaders)
      bodyText <- response.body.asString.mapError(Error.TransportFailure.apply)
      result   <- ZIO
                    .fromEither(bodyText.fromJson[CompletedBlob])
                    .mapError(err => Error.DecodingFailed(response.status, bodyText, err))
    } yield result

  /** Abort an in-flight multipart upload. */
  def abortMultipart(session: MultipartSession): IO[Error, Unit] =
    execute(Method.DELETE, uploadsPath / session.uploadId).unit

  /**
   * Upload a full blob using a streamed request body.
   *
   * @param attributes
   *   optional map of blob attributes persisted alongside the manifest
   * @param data
   *   payload stream (consumed lazily)
   * @param contentType
   *   optional content-type header to propagate to the node
   * @param contentLength
   *   optionally set when the total byte size is known in advance. Enables the
   *   node to enforce quotas without buffering the entire stream in memory.
   */
  def uploadStream(
    attributes: Map[String, String],
    data: ZStream[Any, Throwable, Byte],
    contentType: Option[String] = None,
    contentLength: Option[Long] = None,
  ): IO[Error, CompletedBlob] =
    val typeHeader      = contentType.map(ct => Header.Custom("Content-Type", ct))
    val lengthHeader    = contentLength.map(len => Header.Custom("Content-Length", len.toString))
    val attributeHeader = Header.Custom("X-Attributes", attributes.toJson)
    val headers         = defaultHeaders ++ Headers((typeHeader.toList ++ lengthHeader.toList :+ attributeHeader)*)

    for {
      payload  <- data.runCollect.mapError(Error.TransportFailure.apply)
      response <- execute(Method.POST, blobsPath, Body.fromChunk(payload), headers)
      bodyText <- response.body.asString.mapError(Error.TransportFailure.apply)
      result   <- ZIO
                    .fromEither(bodyText.fromJson[CompletedBlob])
                    .mapError(err => Error.DecodingFailed(response.status, bodyText, err))
    } yield result

  private val jsonHeaders: Headers =
    defaultHeaders ++ Headers(Header.Custom("Content-Type", "application/json"))

  private def execute(method: Method, url: URL, body: Body = Body.empty, headers: Headers = defaultHeaders): IO[Error, Response] =
    ZIO
      .scoped {
        client.request(Request(method = method, url = url, headers = headers, body = body))
      }
      .mapError(Error.TransportFailure.apply)
      .flatMap(response => ensureSuccess(response).as(response))

  private def ensureSuccess(response: Response): IO[Error, Unit] =
    if response.status.isSuccess then ZIO.unit
    else
      response.body.asString
        .mapError(Error.TransportFailure.apply)
        .flatMap(bodyText => ZIO.fail(Error.HttpFailure(response.status, bodyText)))

object UploadNodeHttpClient:

  /** Configuration used when instantiating a client instance. */
  final case class Config(
    baseUrl: URL,
    defaultHeaders: Headers = Headers.empty,
  )

  object Config:
    def fromString(url: String, headers: Headers = Headers.empty): IO[String, Config] =
      ZIO.fromEither(URL.decode(url)).mapError(_.getMessage).map(parsed => Config(parsed, headers))

  /** Supported client level failures. */
  sealed trait Error
  object Error:
    final case class TransportFailure(cause: Throwable)                              extends Error
    final case class HttpFailure(status: Status, body: String)                       extends Error
    final case class DecodingFailed(status: Status, payload: String, reason: String) extends Error

  /** Request payload for initiating a multipart upload. */
  final case class MultipartStartRequest(
    totalSize: Option[Long],
    attributes: Map[String, String],
  ) derives JsonEncoder

  /** Representation of a multipart session returned by the node. */
  final case class MultipartSession(
    uploadId: String,
    expiresAt: Option[Instant],
  ) derives JsonDecoder

  /** Metadata required for uploading a single part. */
  final case class MultipartPartRequest(
    partNumber: Int,
    contentLength: Option[Long],
  )

  /** Acknowledgement returned after a part upload completes. */
  final case class PartAck(
    partNumber: Int,
    etag: String,
    size: Long,
  ) derives JsonDecoder

  /** Payload supplied to finalize the multipart upload. */
  final case class MultipartCompleteRequest(parts: Chunk[CompletedPart]) derives JsonEncoder

  /** Metadata describing an individual part when completing multipart upload. */
  final case class CompletedPart(
    partNumber: Int,
    etag: String,
    size: Long,
  ) derives JsonCodec

  /** Response returned by the upload node once a blob is persisted. */
  final case class CompletedBlob(
    key: String,
    size: Long,
    hash: String,
    attributes: Map[String, String],
  ) derives JsonDecoder

  private val instantFormatter = DateTimeFormatter.ISO_INSTANT

  given JsonDecoder[Instant] =
    JsonDecoder[String].mapOrFail { value =>
      Try(Instant.parse(value)).toEither.left.map(_.getMessage)
    }

  given JsonEncoder[Instant] =
    JsonEncoder[String].contramap(instantFormatter.format)

  def layer(config: Config): ZLayer[Client, Nothing, UploadNodeHttpClient] =
    ZLayer.fromFunction(UploadNodeHttpClient(_, config.baseUrl, config.defaultHeaders))

  def make(config: Config): ZIO[Client, Nothing, UploadNodeHttpClient] =
    ZIO.service[Client].map(UploadNodeHttpClient(_, config.baseUrl, config.defaultHeaders))
