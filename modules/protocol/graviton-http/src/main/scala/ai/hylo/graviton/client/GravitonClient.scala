package ai.hylo.graviton.client

import graviton.shared.ApiModels.*
import graviton.shared.{ApiJson, ApiJsonCodec, MediaTypeText}
import graviton.streams.BoundedByteStream
import graviton.runtime.upload.{UploadHttpHeaders, UploadSessionKey}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import _root_.zio.*
import _root_.zio.blocks.mediatype.MediaType as BlocksMediaType
import _root_.zio.http.*
import _root_.zio.stream.ZStream

import java.nio.charset.StandardCharsets

/** Streaming SDK for the operational `/api/v1/blobs` API. */
final class GravitonClient private (
  client: Client,
  config: GravitonClient.Config,
) {
  import GravitonClient.*

  private val blobsUrl   = config.baseUrl / "api" / "v1" / "blobs"
  private val uploadsUrl = config.baseUrl / "api" / "v1" / "uploads"

  /** Persist a stream without materializing it in the SDK. */
  def upload(upload: Upload): IO[Error, BlobUploadResult] =
    ZIO.fromEither(validatedUploadRequest(upload)).flatMap(executeJson[BlobUploadResult])

  /** Persist through a stable tenant-scoped upload owner without exposing cluster routing. */
  def uploadLocalized(upload: Upload, session: UploadSessionKey): IO[Error, BlobUploadResult] =
    ZIO
      .fromEither(validatedUploadRequest(upload))
      .map(
        _.addHeader(Header.Custom(UploadHttpHeaders.TenantId, s"${session.tenantId.value}"))
          .addHeader(Header.Custom(UploadHttpHeaders.UploadSession, s"${session.uploadSessionId.value}"))
      )
      .flatMap(executeJson[BlobUploadResult])

  /**
   * Crash-safe upload using bounded retry parts. Session identifiers and
   * offsets remain internal unless a caller explicitly opts into the lower
   * level resume methods.
   */
  def uploadResumable(
    upload: Upload,
    partSize: ResumablePartSize = ResumablePartSize.Default,
    onCheckpoint: ResumableUploadStatus => Task[Unit] = _ => ZIO.unit,
  ): IO[Error, ResumableUploadStatus] =
    for
      created   <- createResumable(upload).tap(persistCheckpoint(onCheckpoint))
      appended  <- appendRemaining(created, upload.bytes, partSize, onCheckpoint)
      committed <- commitResumable(appended.id).tap(persistCheckpoint(onCheckpoint))
    yield committed

  /** Create a typed checkpoint for applications that persist their own retry state. */
  def createResumable(upload: Upload): IO[Error, ResumableUploadStatus] =
    for
      contentType <- ZIO.fromEither(
                       MediaTypeText.renderEither(upload.contentType).left.map(Error.InvalidMediaType.apply)
                     )
      sessionId   <- Random.nextUUID.map(value => UploadId.applyUnsafe(value.toString))
      headers      = config.defaultHeaders ++ Headers(
                       Header.Custom("Content-Type", contentType),
                       Header.Custom(UploadHttpHeaders.UploadSession, sessionId.value),
                     ) ++ upload.contentLength.fold(Headers.empty)(length =>
                       Headers(Header.Custom(UploadHttpHeaders.UploadLength, length.value.toString))
                     )
      request      = Request(method = Method.POST, url = uploadsUrl, headers = headers)
      create       = executeJson[ResumableUploadStatus](request).catchSome {
                       // A response can be lost after durable creation. Every retry
                       // reuses the same client-generated ID and resolves conflict
                       // by reading that checkpoint.
                       case Error.UnexpectedStatus(Status.Conflict, body) if body.contains("upload_exists") =>
                         resumableStatus(sessionId)
                     }
      status      <- retryControl(create)
    yield status

  /**
   * Resume from a previously returned checkpoint. `remaining` must begin at
   * `checkpoint.offset`; this avoids silently re-reading or dropping a large
   * caller-owned source.
   */
  def resumeResumable(
    checkpoint: ResumableUploadStatus,
    remaining: ZStream[Any, Throwable, Byte],
    partSize: ResumablePartSize = ResumablePartSize.Default,
    onCheckpoint: ResumableUploadStatus => Task[Unit] = _ => ZIO.unit,
  ): IO[Error, ResumableUploadStatus] =
    appendRemaining(checkpoint, remaining, partSize, onCheckpoint)

  def resumableStatus(id: UploadId): IO[Error, ResumableUploadStatus] =
    retryControl(executeJson[ResumableUploadStatus](Request.get(uploadsUrl / id.value).copy(headers = config.defaultHeaders)))

  def commitResumable(id: UploadId): IO[Error, ResumableUploadStatus] =
    retryControl(
      executeJson[ResumableUploadStatus](
        Request(method = Method.POST, url = uploadsUrl / id.value / "commit", headers = config.defaultHeaders)
      )
    )

  def cancelResumable(id: UploadId): IO[Error, Unit] =
    execute(Request(method = Method.DELETE, url = uploadsUrl / id.value, headers = config.defaultHeaders))(_ => ZIO.unit)

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
    metadataPage(id)

  /** Read one bounded manifest page. Follow `nextCursor` until it is absent. */
  def metadataPage(
    id: BlobId,
    limit: ManifestPageLimit = ManifestPageLimit.Default,
    cursor: Option[ManifestCursor] = None,
  ): IO[Error, BlobDetails] =
    val withLimit = (blobUrl(id) / "metadata").addQueryParam("limit", limit.value.toString)
    val target    = cursor.fold(withLimit)(value => withLimit.addQueryParam("cursor", value.value))
    executeJson[BlobDetails](Request.get(target).copy(headers = config.defaultHeaders))

  def verify(id: BlobId): IO[Error, BlobVerificationResult] =
    executeJson[BlobVerificationResult](
      Request(method = Method.POST, url = blobUrl(id) / "verify", headers = config.defaultHeaders)
    )

  def list(limit: ListLimit = ListLimit.Default, cursor: Option[ListCursor] = None): IO[Error, BlobListResponse] =
    val withLimit = blobsUrl.addQueryParam("limit", limit.value.toString)
    val target    = cursor.fold(withLimit)(value => withLimit.addQueryParam("cursor", value.value))
    executeJson[BlobListResponse](Request.get(target).copy(headers = config.defaultHeaders))

  def delete(id: BlobId): IO[Error, Unit] =
    execute(Request(method = Method.DELETE, url = blobUrl(id), headers = config.defaultHeaders))(_ => ZIO.unit)

  /** Retained for binary compatibility with the 0.4.0 SDK implementation. */
  private[client] def uploadRequest(upload: Upload): Request =
    buildUploadRequest(upload, upload.contentType.fullType)

  private def validatedUploadRequest(upload: Upload): Either[Error, Request] =
    MediaTypeText
      .renderEither(upload.contentType)
      .left
      .map(Error.InvalidMediaType.apply)
      .map(buildUploadRequest(upload, _))

  private def buildUploadRequest(upload: Upload, contentType: String): Request =
    val body = upload.contentLength match
      case Some(length) => Body.fromStream(upload.bytes, length.value)
      case None         => Body.fromStreamChunked(upload.bytes)
    Request(
      method = Method.POST,
      url = blobsUrl,
      headers = config.defaultHeaders ++ Headers(Header.Custom("Content-Type", contentType)),
      body = body,
    )

  private def appendRemaining(
    initial: ResumableUploadStatus,
    remaining: ZStream[Any, Throwable, Byte],
    partSize: ResumablePartSize,
    onCheckpoint: ResumableUploadStatus => Task[Unit],
  ): IO[Error, ResumableUploadStatus] =
    for
      current <- Ref.make(initial)
      _       <- remaining
                   .rechunk(partSize.value)
                   .chunks
                   .mapZIO { bytes =>
                     for
                       bounded    <- ZIO.fromEither(
                                       bytes
                                         .refineEither[ResumablePartBytes.Constraint]
                                         .left
                                         .map(_ => Error.ProtocolViolation("SDK produced an empty or oversized resumable part"))
                                     )
                       checkpoint <- current.get
                       partId     <- Random.nextUUID.map(_.toString)
                       request     = Request(
                                       method = Method.PATCH,
                                       url = uploadsUrl / checkpoint.id.value,
                                       headers = config.defaultHeaders ++ Headers(
                                         Header.Custom(UploadHttpHeaders.UploadOffset, checkpoint.offset.value.toString),
                                         Header.Custom(UploadHttpHeaders.UploadPartId, partId),
                                         Header.Custom("Content-Length", bounded.length.toString),
                                       ),
                                       body = Body.fromStream(ZStream.fromChunk(bounded), bounded.length.toLong),
                                     )
                       updated    <- retryControl(executeJsonOrHeaders(request, checkpoint))
                       expected    = checkpoint.offset.value + bounded.length.toLong
                       _          <- ZIO
                                       .fail(Error.ProtocolViolation(s"append returned offset ${updated.offset.value}, expected $expected"))
                                       .unless(updated.offset.value == expected)
                       _          <- current.set(updated)
                       _          <- persistCheckpoint(onCheckpoint)(updated)
                     yield ()
                   }
                   .runDrain
                   .mapError {
                     case value: Error => value
                     case value        => Error.TransportFailure(value)
                   }
      result  <- current.get
    yield result

  private def persistCheckpoint(
    sink: ResumableUploadStatus => Task[Unit]
  )(status: ResumableUploadStatus): IO[Error, Unit] =
    sink(status).mapError(Error.CheckpointFailure.apply)

  private def executeJsonOrHeaders(
    request: Request,
    previous: ResumableUploadStatus,
  ): IO[Error, ResumableUploadStatus] =
    execute(request) { response =>
      response.headers.get(UploadHttpHeaders.UploadOffset) match
        case Some(raw) =>
          ZIO
            .fromEither(raw.toLongOption.toRight(Error.ProtocolViolation("append response has an invalid Upload-Offset")))
            .flatMap(value => ZIO.fromEither(UploadOffsetBytes.either(value).left.map(Error.ProtocolViolation.apply)))
            .map(offset => previous.copy(offset = offset))
        case None      => ZIO.fail(Error.ProtocolViolation("append response is missing Upload-Offset"))
    }

  private def blobUrl(id: BlobId): URL = blobsUrl / id.value

  private def retryControl[A](effect: => IO[Error, A]): IO[Error, A] =
    def loop(remaining: Int, delay: Duration): IO[Error, A] =
      effect.catchAll { error =>
        if remaining > 0 && retryable(error) then
          val doubled = delay * 2L
          val next    = if doubled > config.resumableRetryMaxDelay then config.resumableRetryMaxDelay else doubled
          ZIO.sleep(delay) *> loop(remaining - 1, next)
        else ZIO.fail(error)
      }

    loop(config.resumableRetryLimit.value, config.resumableRetryBaseDelay)

  private def retryable(error: Error): Boolean =
    error match
      case _: Error.TransportFailure            => true
      case Error.UnexpectedStatus(status, body) =>
        status.code == 429 || status.code >= 500 || (status == Status.Conflict && body.contains("upload_busy"))
      case _                                    => false

  private def executeJson[A: ApiJsonCodec](request: Request): IO[Error, A] =
    execute(request) { response =>
      responseText(response).flatMap { text =>
        ZIO.fromEither(ApiJson.decode[A](text)).mapError(reason => Error.DecodingFailure(response.status, text, reason))
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

  /** Opaque inventory continuation returned by a blob-list response. */
  type ListCursor = ListCursor.T
  object ListCursor extends RefinedSubtype[String, MinLength[1] & MaxLength[16384]]

  /** Bounded number of block descriptions returned from one manifest page. */
  type ManifestPageLimit = ManifestPageLimit.T
  object ManifestPageLimit extends RefinedSubtype[Int, GreaterEqual[1] & LessEqual[1000]]:
    val Default: ManifestPageLimit = applyUnsafe(100)

  /** Opaque server cursor tied to one exact blob identity. */
  type ManifestCursor = ManifestCursor.T
  object ManifestCursor extends RefinedSubtype[String, MinLength[1] & MaxLength[16384]]

  type ResumablePartSize = ResumablePartSize.T
  object ResumablePartSize extends RefinedSubtype[Int, GreaterEqual[1] & LessEqual[67108864]]:
    val Default: ResumablePartSize = applyUnsafe(8 * 1024 * 1024)

  /** The only materialized upload payload in the SDK, statically bounded to 64 MiB. */
  type ResumablePartBytes = Chunk[Byte] :| ResumablePartBytes.Constraint
  object ResumablePartBytes:
    type Constraint = MinLength[1] & MaxLength[67108864]

  type ResumableRetryLimit = ResumableRetryLimit.T
  object ResumableRetryLimit extends RefinedSubtype[Int, GreaterEqual[0] & LessEqual[20]]:
    val Default: ResumableRetryLimit = applyUnsafe(5)

  final case class Config(
    baseUrl: URL,
    defaultHeaders: Headers = Headers.empty,
    resumableRetryLimit: ResumableRetryLimit = ResumableRetryLimit.Default,
    resumableRetryBaseDelay: Duration = 100.millis,
    resumableRetryMaxDelay: Duration = 2.seconds,
  ):
    require(resumableRetryBaseDelay > Duration.Zero, "resumableRetryBaseDelay must be positive")
    require(resumableRetryMaxDelay >= resumableRetryBaseDelay, "resumableRetryMaxDelay must not be below the base delay")

    /** Binary-compatible constructor for the public SDK configuration shipped in 0.6.1. */
    def this(baseUrl: URL, defaultHeaders: Headers) =
      this(baseUrl, defaultHeaders, ResumableRetryLimit.Default, 100.millis, 2.seconds)

    /** Binary-compatible copy method for the public SDK configuration shipped in 0.6.1. */
    def copy(baseUrl: URL, defaultHeaders: Headers): Config =
      new Config(baseUrl, defaultHeaders, resumableRetryLimit, resumableRetryBaseDelay, resumableRetryMaxDelay)

  object Config:
    /** Binary-compatible factory for the public SDK configuration shipped in 0.6.1. */
    def apply(baseUrl: URL, defaultHeaders: Headers): Config = new Config(baseUrl, defaultHeaders)

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

    final case class InvalidMediaType(reason: String) extends Error:
      override def message: String = s"Invalid upload media type: $reason"

    final case class ProtocolViolation(reason: String) extends Error:
      override def message: String = s"Invalid resumable-upload response: $reason"

    final case class CheckpointFailure(cause: Throwable) extends Error:
      override def message: String = Option(cause.getMessage).getOrElse("failed to persist resumable-upload checkpoint")

  def make(config: Config): ZIO[Client, Nothing, GravitonClient] =
    ZIO.service[Client].map(new GravitonClient(_, config))

  def layer(config: Config): ZLayer[Client, Nothing, GravitonClient] =
    ZLayer.fromZIO(make(config))
}
