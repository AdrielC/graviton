package graviton.protocol.http

import graviton.streams.BoundedByteStream
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import zio.*
import zio.blocks.mediatype.MediaType as BlocksMediaType
import zio.http.*
import zio.json.*
import zio.stream.ZStream

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.nio.charset.StandardCharsets
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
    execute(Method.POST, uploadsPath, Body.fromString(request.toJson), jsonHeaders)(decode[MultipartSession])

  /** Upload an individual part inside an existing multipart session. */
  def uploadPart(
    session: MultipartSession,
    request: MultipartPartRequest,
    bytes: ZStream[Any, Throwable, Byte],
  ): IO[Error, PartAck] =
    val target = uploadsPath / session.uploadId.value / "parts" / request.partNumber.toString
    val body   = request.contentLength match
      case Some(length) => Body.fromStream(bytes, length.value)
      case None         => Body.fromStreamChunked(bytes)

    execute(Method.PUT, target, body, defaultHeaders)(decode[PartAck])

  /** Complete a multipart upload by supplying the collected part metadata. */
  def completeMultipart(session: MultipartSession, request: MultipartCompleteRequest): IO[Error, CompletedBlob] =
    execute(
      Method.POST,
      uploadsPath / session.uploadId.value / "complete",
      Body.fromString(request.toJson),
      jsonHeaders,
    )(decode[CompletedBlob])

  /** Abort an in-flight multipart upload. */
  def abortMultipart(session: MultipartSession): IO[Error, Unit] =
    execute(Method.DELETE, uploadsPath / session.uploadId.value)(_ => ZIO.unit)

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
    contentType: Option[BlocksMediaType] = None,
    contentLength: Option[ByteLength] = None,
  ): IO[Error, CompletedBlob] =
    val typeHeader      = contentType.map(ct => Header.Custom("Content-Type", ct.fullType))
    val attributeHeader = Header.Custom("X-Attributes", attributes.toJson)
    val headers         = defaultHeaders ++ Headers((typeHeader.toList :+ attributeHeader)*)
    val body            = contentLength match
      case Some(length) => Body.fromStream(data, length.value)
      case None         => Body.fromStreamChunked(data)

    execute(Method.POST, blobsPath, body, headers)(decode[CompletedBlob])

  private val jsonHeaders: Headers =
    defaultHeaders ++ Headers(Header.Custom("Content-Type", "application/json"))

  private def execute[A](
    method: Method,
    url: URL,
    body: Body = Body.empty,
    headers: Headers = defaultHeaders,
  )(consume: Response => IO[Error, A]): IO[Error, A] =
    ZIO.scoped {
      client(Request(method = method, url = url, headers = headers, body = body))
        .mapError(Error.TransportFailure.apply)
        .flatMap { response =>
          if response.status.isSuccess then consume(response)
          else responseText(response).flatMap(bodyText => ZIO.fail(Error.HttpFailure(response.status, bodyText)))
        }
    }

  private def decode[A: JsonDecoder](response: Response): IO[Error, A] =
    responseText(response).flatMap { bodyText =>
      ZIO
        .fromEither(bodyText.fromJson[A])
        .mapError(err => Error.DecodingFailed(response.status, bodyText, err))
    }

  private def responseText(response: Response): IO[Error, String] =
    BoundedByteStream
      .collectControlPlane(response.body.asStream)
      .map(bytes => new String(bytes.toArray, StandardCharsets.UTF_8))
      .mapError {
        case _: BoundedByteStream.LimitExceeded => Error.ResponseTooLarge(BoundedByteStream.MaxControlPlaneBytes.toLong)
        case cause                              => Error.TransportFailure(cause)
      }

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
    final case class ResponseTooLarge(limit: Long)                                   extends Error

  type UploadId = UploadId.T
  object UploadId extends RefinedSubtype[String, Match["[A-Za-z0-9._~-]+"] & MinLength[1] & MaxLength[128]]:
    given JsonCodec[UploadId] = summon[JsonCodec[String]].transformOrFail(either, _.value)

  type ByteLength = ByteLength.T
  object ByteLength extends RefinedSubtype[Long, GreaterEqual[1L] & LessEqual[1099511627776L]]:
    given JsonCodec[ByteLength] = summon[JsonCodec[Long]].transformOrFail(either, _.value)

  /** Request payload for initiating a multipart upload. */
  final case class MultipartStartRequest(
    totalSize: Option[ByteLength],
    attributes: Map[String, String],
  ) derives JsonEncoder

  /** Representation of a multipart session returned by the node. */
  final case class MultipartSession(
    uploadId: UploadId,
    expiresAt: Option[Instant],
  ) derives JsonDecoder

  /** Metadata required for uploading a single part. */
  final case class MultipartPartRequest(
    partNumber: Int,
    contentLength: Option[ByteLength],
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
