package ai.hylo.graviton.client

import graviton.shared.ApiModels.*
import graviton.streams.BoundedByteStream
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import zio.*
import zio.blocks.mediatype.MediaType as BlocksMediaType
import zio.http.*
import zio.json.*
import zio.stream.ZStream

import java.nio.charset.StandardCharsets

/** Streaming SDK for the operational `/api/v1/blobs` API. */
final class GravitonClient private (
  client: Client,
  config: GravitonClient.Config,
) {
  import GravitonClient.*

  private val blobsUrl = config.baseUrl / "api" / "v1" / "blobs"

  /** Persist a stream without materializing it in the SDK. */
  def upload(upload: Upload): IO[Error, BlobUploadResult] =
    executeJson[BlobUploadResult](uploadRequest(upload))

  /** Lazily download a full blob or one byte range while retaining response scope. */
  def download(id: BlobId, range: Option[DownloadRange] = None): ZStream[Any, Error, Byte] =
    val rangeHeaders = range.fold(Headers.empty)(value => Headers(Header.Custom("Range", value.render)))
    val request      = Request.get(blobUrl(id)).copy(headers = config.defaultHeaders ++ rangeHeaders)

    ZStream.unwrapScoped {
      client(request)
        .mapError(Error.TransportFailure.apply)
        .map { response =>
          if response.status == Status.Ok || response.status == Status.PartialContent then
            response.body.asStream.mapError(Error.TransportFailure.apply)
          else ZStream.fromZIO(unexpected(response))
        }
    }

  def metadata(id: BlobId): IO[Error, BlobDetails] =
    executeJson[BlobDetails](Request.get(blobUrl(id) / "metadata").copy(headers = config.defaultHeaders))

  def verify(id: BlobId): IO[Error, BlobVerificationResult] =
    executeJson[BlobVerificationResult](
      Request(method = Method.POST, url = blobUrl(id) / "verify", headers = config.defaultHeaders)
    )

  def list(limit: ListLimit = ListLimit.Default, cursor: Option[BlobId] = None): IO[Error, BlobListResponse] =
    val withLimit = blobsUrl.addQueryParam("limit", limit.value.toString)
    val target    = cursor.fold(withLimit)(id => withLimit.addQueryParam("cursor", id.value))
    executeJson[BlobListResponse](Request.get(target).copy(headers = config.defaultHeaders))

  def delete(id: BlobId): IO[Error, Unit] =
    execute(Request(method = Method.DELETE, url = blobUrl(id), headers = config.defaultHeaders))(_ => ZIO.unit)

  private[client] def uploadRequest(upload: Upload): Request =
    val body = upload.contentLength match
      case Some(length) => Body.fromStream(upload.bytes, length.value)
      case None         => Body.fromStreamChunked(upload.bytes)
    Request(
      method = Method.POST,
      url = blobsUrl,
      headers = config.defaultHeaders ++ Headers(Header.Custom("Content-Type", upload.contentType.fullType)),
      body = body,
    )

  private def blobUrl(id: BlobId): URL = blobsUrl / id.value

  private def executeJson[A: JsonDecoder](request: Request): IO[Error, A] =
    execute(request) { response =>
      responseText(response).flatMap { text =>
        ZIO.fromEither(text.fromJson[A]).mapError(reason => Error.DecodingFailure(response.status, text, reason))
      }
    }

  private def execute[A](request: Request)(consume: Response => IO[Error, A]): IO[Error, A] =
    ZIO.scoped {
      client(request)
        .mapError(Error.TransportFailure.apply)
        .flatMap(response => if response.status.isSuccess then consume(response) else unexpected(response))
    }

  private def unexpected(response: Response): IO[Error, Nothing] =
    responseText(response).flatMap(body => ZIO.fail(Error.UnexpectedStatus(response.status, body)))

  private def responseText(response: Response): IO[Error, String] =
    BoundedByteStream
      .collectControlPlane(response.body.asStream)
      .map(bytes => new String(bytes.toArray, StandardCharsets.UTF_8))
      .mapError {
        case _: BoundedByteStream.LimitExceeded => Error.ResponseTooLarge(BoundedByteStream.MaxControlPlaneBytes.toLong)
        case cause                              => Error.TransportFailure(cause)
      }
}

object GravitonClient {

  type BlobByteLength = BlobByteLength.T
  object BlobByteLength extends RefinedSubtype[Long, GreaterEqual[1L] & LessEqual[1099511627776L]]

  type ByteOffset = ByteOffset.T
  object ByteOffset extends RefinedSubtype[Long, GreaterEqual[0L] & LessEqual[1099511627775L]]

  type ListLimit = ListLimit.T
  object ListLimit extends RefinedSubtype[Int, GreaterEqual[1] & LessEqual[1000]]:
    val Default: ListLimit = applyUnsafe(100)

  final case class Config(
    baseUrl: URL,
    defaultHeaders: Headers = Headers.empty,
  )

  final case class Upload(
    bytes: ZStream[Any, Throwable, Byte],
    contentType: BlocksMediaType,
    contentLength: Option[BlobByteLength],
  )

  final case class DownloadRange private (start: ByteOffset, endInclusive: ByteOffset):
    private[client] def render: String = s"bytes=${start.value}-${endInclusive.value}"

  object DownloadRange:
    def make(start: Long, endInclusive: Long): Either[String, DownloadRange] =
      for
        refinedStart <- ByteOffset.either(start)
        refinedEnd   <- ByteOffset.either(endInclusive)
        _            <- Either.cond(refinedEnd.value >= refinedStart.value, (), "Range end must not precede range start")
      yield DownloadRange(refinedStart, refinedEnd)

  sealed trait Error extends Throwable:
    def message: String
    override def getMessage: String = message

  object Error:
    final case class TransportFailure(cause: Throwable) extends Error:
      override def message: String = Option(cause.getMessage).getOrElse("transport failure")

    final case class UnexpectedStatus(status: Status, body: String) extends Error:
      override def message: String = s"Unexpected status ${status.code}: $body"

    final case class DecodingFailure(status: Status, body: String, reason: String) extends Error:
      override def message: String = s"Failed to decode ${status.code}: $reason"

    final case class ResponseTooLarge(limit: Long) extends Error:
      override def message: String = s"Control-plane response exceeds $limit bytes"

  def make(config: Config): ZIO[Client, Nothing, GravitonClient] =
    ZIO.service[Client].map(new GravitonClient(_, config))

  def layer(config: Config): ZLayer[Client, Nothing, GravitonClient] =
    ZLayer.fromZIO(make(config))
}
