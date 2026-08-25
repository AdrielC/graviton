package ai.hylo.graviton.client

import graviton.shared.ApiModels.BlobId
import graviton.streams.BoundedByteStream
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import zio.*
import zio.blocks.mediatype.MediaType as BlocksMediaType
import zio.http.*
import zio.json.*
import zio.stream.ZStream

import java.nio.charset.StandardCharsets

/**
 * Client for the experimental resumable-upload protocol.
 *
 * Upload payloads remain streaming. Session identity is a scoped, fiber-local
 * capability, so part and completion calls cannot accidentally mix raw string
 * identifiers between concurrent uploads.
 */
final class GravitonUploadHttpClient private (
  baseUrl: URL,
  uploadsPrefix: Path,
  defaultHeaders: Headers,
  sessionRef: FiberRef[Option[GravitonUploadHttpClient.SessionId]],
  transport: Request => ZIO[Scope, Throwable, Response],
) {

  import GravitonUploadHttpClient.*

  private val jsonHeaders: Headers = Headers(Header.ContentType(zio.http.MediaType.application.json))

  def register(request: RegisterRequest): IO[Error, RegisterResponse] =
    execute(Method.POST, uploadsPrefix, Body.fromString(request.toJson), jsonHeaders)(bodyAs[RegisterResponse])

  /** Run an effect inside a newly registered upload session. */
  def withUploadSession[A](request: RegisterRequest)(effect: IO[Error, A]): IO[Error, A] =
    register(request).flatMap(registered => sessionRef.locally(Some(registered.sessionId))(effect))

  /** Resume a known session without exposing it on every operation. */
  def withSession[R, E, A](sessionId: SessionId)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    sessionRef.locally(Some(sessionId))(effect)

  def uploadPart(part: UploadPart, bytes: ZStream[Any, Throwable, Byte]): IO[Error, UploadAckPayload] =
    currentSession.flatMap { sessionId =>
      val target  = uploadsPrefix / sessionId.value / "parts" / part.sequence.value.toString
      val body    = part.contentLength match
        case Some(length) => Body.fromStream(bytes, length.value)
        case None         => Body.fromStreamChunked(bytes)
      val headers = Headers(Header.Custom("Content-Type", part.contentType.fullType))
      execute(Method.PUT, target, body, headers)(bodyAs[UploadAckPayload])
    }

  def complete(request: CompleteRequest): IO[Error, CompleteResponse] =
    currentSession.flatMap { sessionId =>
      execute(
        Method.POST,
        uploadsPrefix / sessionId.value / "complete",
        Body.fromString(request.toJson),
        jsonHeaders,
      )(bodyAs[CompleteResponse])
    }

  def uploadOneShot(
    request: RegisterRequest,
    data: ZStream[Any, Throwable, Byte],
    checksum: Option[String],
  ): IO[Error, CompleteResponse] =
    withUploadSession(request) {
      for {
        _         <- uploadPart(
                       UploadPart(
                         sequence = PartSequence.applyUnsafe(0L),
                         offset = ByteOffset.applyUnsafe(0L),
                         last = true,
                         checksum = checksum,
                         contentType = request.objectContentType,
                         contentLength = request.totalSize,
                       ),
                       data,
                     )
        completed <- complete(
                       CompleteRequest(
                         expectedObjectHash = checksum,
                         manifestContentType = None,
                         manifestBlobId = None,
                       )
                     )
      } yield completed
    }

  private def currentSession: IO[Error, SessionId] =
    sessionRef.get.flatMap(ZIO.fromOption(_).orElseFail(Error.MissingSession))

  private def execute[A](method: Method, path: Path, body: Body, headers: Headers)(
    consume: Response => IO[Error, A]
  ): IO[Error, A] = {
    val url     = baseUrl.copy(path = baseUrl.path ++ path)
    val request = Request(
      method = method,
      url = url,
      headers = defaultHeaders ++ headers,
      body = body,
    )

    ZIO.scoped {
      transport(request)
        .mapError(Error.TransportFailure.apply)
        .flatMap { response =>
          if response.status.isSuccess then consume(response)
          else responseText(response).flatMap(text => ZIO.fail(Error.UnexpectedStatus(response.status, text)))
        }
    }
  }

  private def bodyAs[A: JsonDecoder](response: Response): IO[Error, A] =
    responseText(response).flatMap { text =>
      ZIO.fromEither(text.fromJson[A]).mapError(err => Error.DecodingFailure(response.status, text, err))
    }

  private def responseText(response: Response): IO[Error, String] =
    BoundedByteStream
      .collectControlPlane(response.body.asStream)
      .map(bytes => new String(bytes.toArray, StandardCharsets.UTF_8))
      .mapError {
        case _: BoundedByteStream.LimitExceeded => Error.ResponseTooLarge(BoundedByteStream.MaxControlPlaneBytes.toLong)
        case cause                              => Error.TransportFailure(cause)
      }
}

object GravitonUploadHttpClient {

  type SessionId = SessionId.T
  object SessionId extends RefinedSubtype[String, Match["[A-Za-z0-9._~-]+"] & MinLength[1] & MaxLength[128]]:
    given JsonCodec[SessionId] = summon[JsonCodec[String]].transformOrFail(either, _.value)

  type UploadByteLength = UploadByteLength.T
  object UploadByteLength extends RefinedSubtype[Long, GreaterEqual[1L] & LessEqual[1099511627776L]]:
    given JsonCodec[UploadByteLength] = summon[JsonCodec[Long]].transformOrFail(either, _.value)

  type ByteOffset = ByteOffset.T
  object ByteOffset extends RefinedSubtype[Long, GreaterEqual[0L] & LessEqual[1099511627776L]]

  type PartSequence = PartSequence.T
  object PartSequence extends RefinedSubtype[Long, GreaterEqual[0L]]

  type MetadataNamespace = MetadataNamespace.T
  object MetadataNamespace extends RefinedSubtype[String, Match["[A-Za-z0-9][A-Za-z0-9._:-]*"] & MaxLength[128]]:
    given JsonCodec[MetadataNamespace] = summon[JsonCodec[String]].transformOrFail(either, _.value)

  given JsonCodec[BlocksMediaType] =
    summon[JsonCodec[String]].transformOrFail(BlocksMediaType.parse, _.fullType)

  final case class RegisterRequest(
    objectContentType: BlocksMediaType,
    totalSize: Option[UploadByteLength],
    metadata: Chunk[MetadataNamespacePayload],
    clientSessionId: Option[SessionId],
  ) derives JsonEncoder

  final case class RegisterResponse(sessionId: SessionId, ttlSeconds: Long) derives JsonDecoder

  final case class UploadPart(
    sequence: PartSequence,
    offset: ByteOffset,
    last: Boolean,
    checksum: Option[String],
    contentType: BlocksMediaType,
    contentLength: Option[UploadByteLength],
  )

  final case class UploadAckPayload(
    sessionId: SessionId,
    acknowledgedSequence: Long,
    receivedBytes: Long,
  ) derives JsonDecoder

  final case class CompleteRequest(
    expectedObjectHash: Option[String],
    manifestContentType: Option[BlocksMediaType],
    manifestBlobId: Option[BlobId],
  ) derives JsonEncoder

  final case class CompleteResponse(
    documentId: String,
    blobHash: String,
    objectContentType: BlocksMediaType,
    finalUrl: Option[String],
  ) derives JsonDecoder

  /** Metadata is uploaded separately as a stream and referenced by content address. */
  final case class MetadataReference(
    contentType: BlocksMediaType,
    blobId: BlobId,
  ) derives JsonCodec

  final case class MetadataNamespacePayload(
    namespace: MetadataNamespace,
    schema: Option[MetadataReference],
    data: MetadataReference,
  ) derives JsonCodec

  sealed trait Error extends Throwable {
    def message: String
    override def getMessage: String = message
  }

  object Error {
    final case class TransportFailure(cause: Throwable) extends Error {
      override def message: String = Option(cause.getMessage).getOrElse("transport failure")
    }

    final case class UnexpectedStatus(status: Status, body: String) extends Error {
      override def message: String = s"Unexpected status ${status.code}: $body"
    }

    final case class DecodingFailure(status: Status, body: String, reason: String) extends Error {
      override def message: String = s"Failed to decode ${status.code}: $reason"
    }

    final case class ResponseTooLarge(limit: Long) extends Error {
      override def message: String = s"Control-plane response exceeds $limit bytes"
    }

    case object MissingSession extends Error {
      override def message: String = "No upload session is active in this fiber"
    }
  }

  def fromTransport(
    baseUrl: URL,
    uploadsPrefix: Path = Path.root / "api" / "uploads",
    defaultHeaders: Headers = Headers.empty,
  )(
    transport: Request => ZIO[Scope, Throwable, Response]
  ): ZIO[Scope, Nothing, GravitonUploadHttpClient] =
    FiberRef
      .make[Option[SessionId]](None, identity, (parent, _) => parent)
      .map(new GravitonUploadHttpClient(baseUrl, uploadsPrefix, defaultHeaders, _, transport))

  def make(
    baseUrl: URL,
    uploadsPrefix: Path = Path.root / "api" / "uploads",
    defaultHeaders: Headers = Headers.empty,
  ): ZIO[Client & Scope, Nothing, GravitonUploadHttpClient] =
    ZIO.serviceWithZIO[Client] { client =>
      fromTransport(baseUrl, uploadsPrefix, defaultHeaders)(client(_))
    }
}
