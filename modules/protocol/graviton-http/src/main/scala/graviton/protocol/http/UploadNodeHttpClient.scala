package graviton.protocol.http

import graviton.runtime.upload.{DocumentId, UploadedPart}
import zio.*
import zio.http.*
import zio.json.*
import zio.stream.*

final case class MultipartStartRequest(
  fileName: Option[String],
  totalSize: Option[Long],
  mediaType: Option[String],
  metadata: Map[String, Map[String, String]],
  preferredChunkSize: Option[Int],
  partChecksums: Map[Int, String],
  wholeFileChecksum: Option[String],
)

object MultipartStartRequest:
  val empty: MultipartStartRequest = MultipartStartRequest(None, None, None, Map.empty, None, Map.empty, None)

final case class MultipartSession(
  uploadId: String,
  chunkSize: Int,
  maxChunks: Int,
  expiresAtEpochSeconds: Option[Long],
)

final case class MultipartPartRequest(
  partNumber: Int,
  offset: Long,
  contentLength: Option[Long],
  checksum: Option[String],
)

final case class PartAck(
  partNumber: Int,
  acknowledgedSequence: Int,
  receivedBytes: Long,
)

final case class CompletionRequest(
  parts: Chunk[UploadedPart],
  expectedChecksum: Option[String],
)

final case class CompletionResult(
  documentId: DocumentId,
  attributes: Map[String, String],
)

sealed trait UploadNodeHttpError extends Product with Serializable:
  def message: String

object UploadNodeHttpError:
  final case class TransportFailure(cause: Throwable)                           extends UploadNodeHttpError:
    override def message: String = cause.getMessage
  final case class UnexpectedStatus(status: Status, body: String)               extends UploadNodeHttpError:
    override def message: String = s"Unexpected status ${status.code} with body: $body"
  final case class DecodingFailed(status: Status, body: String, reason: String) extends UploadNodeHttpError:
    override def message: String = s"Failed to decode ${status.code}: $reason"
  final case class EncodingFailed(message: String)                              extends UploadNodeHttpError
  final case class ProtocolViolation(message: String)                           extends UploadNodeHttpError

final class UploadNodeHttpClient(
  baseUrl: URL,
  uploadsPrefix: Path,
  transport: Request => Task[Response],
  defaultHeaders: Headers = Headers.empty,
):

  private given JsonCodec[MultipartStartRequestPayload] = DeriveJsonCodec.gen
  private given JsonCodec[MultipartSessionPayload]      = DeriveJsonCodec.gen
  private given JsonCodec[PartAckPayload]               = DeriveJsonCodec.gen
  private given JsonCodec[CompletionRequestPayload]     = DeriveJsonCodec.gen
  private given JsonCodec[CompletionResultPayload]      = DeriveJsonCodec.gen

  def startMultipart(request: MultipartStartRequest): IO[UploadNodeHttpError, MultipartSession] =
    encodeJson(MultipartStartRequestPayload.fromDomain(request)).flatMap { body =>
      execute(Method.POST, uploadsPrefix, Body.fromString(body), jsonHeaders)
        .flatMap(decodeJson[MultipartSessionPayload])
        .map(_.toDomain)
    }

  def uploadPart(
    session: MultipartSession,
    part: MultipartPartRequest,
    bytes: ZStream[Any, Throwable, Byte],
  ): IO[UploadNodeHttpError, PartAck] =
    val target  = uploadsPrefix / session.uploadId / "parts" / part.partNumber.toString
    val headers = part.contentLength match
      case Some(length) => jsonHeaders ++ Headers(Header.Custom("Content-Length", length.toString))
      case None         => jsonHeaders

    execute(Method.PUT, target, Body.fromStream(bytes), headers)
      .flatMap { response =>
        decodeJson[PartAckPayload](response)
      }
      .map(_.toDomain)

  def complete(
    session: MultipartSession,
    request: CompletionRequest,
  ): IO[UploadNodeHttpError, CompletionResult] =
    encodeJson(CompletionRequestPayload.fromDomain(request))
      .flatMap { body =>
        val target = uploadsPrefix / session.uploadId / "complete"
        execute(Method.POST, target, Body.fromString(body), jsonHeaders)
      }
      .flatMap(decodeJson[CompletionResultPayload])
      .map(_.toDomain)

  def uploadOneShot(
    request: MultipartStartRequest,
    payload: ZStream[Any, Throwable, Byte],
  ): IO[UploadNodeHttpError, CompletionResult] =
    for {
      session <- startMultipart(request)
      total    = request.totalSize.getOrElse(0L)
      part     = UploadedPart(partNumber = 1, offset = 0L, size = total, checksum = request.partChecksums.get(1))
      _       <- uploadPart(
                   session,
                   MultipartPartRequest(1, 0L, request.totalSize, request.partChecksums.get(1)),
                   payload,
                 )
      result  <- complete(session, CompletionRequest(Chunk.single(part), request.wholeFileChecksum))
    } yield result

  private def execute(
    method: Method,
    relative: Path,
    body: Body,
    headers: Headers,
  ): IO[UploadNodeHttpError, Response] =
    for {
      url      <- ZIO.succeed(baseUrl.copy(path = baseUrl.path ++ relative))
      request   = Request(
                    method = method,
                    url = url,
                    headers = defaultHeaders ++ headers,
                    body = body,
                  )
      response <- transport(request).mapError(UploadNodeHttpError.TransportFailure.apply)
      _        <- if response.status.isSuccess then ZIO.unit
                  else
                    response.body.asString
                      .mapError(UploadNodeHttpError.TransportFailure.apply)
                      .flatMap(body => ZIO.fail(UploadNodeHttpError.UnexpectedStatus(response.status, body)))
    } yield response

  private def decodeJson[A: JsonDecoder](response: Response): IO[UploadNodeHttpError, A] =
    response.body.asString
      .mapError(UploadNodeHttpError.TransportFailure.apply)
      .flatMap { json =>
        ZIO.fromEither(json.fromJson[A]).mapError(err => UploadNodeHttpError.DecodingFailed(response.status, json, err))
      }

  private def encodeJson[A: JsonEncoder](payload: A): IO[UploadNodeHttpError, String] =
    ZIO.succeed(payload.toJson)

  private val jsonHeaders: Headers = Headers(Header.ContentType(MediaType.application.json))

  private final case class MultipartStartRequestPayload(
    fileName: Option[String],
    totalSize: Option[Long],
    mediaType: Option[String],
    metadata: Map[String, Map[String, String]],
    preferredChunkSize: Option[Int],
    partChecksums: Map[Int, String],
    wholeFileChecksum: Option[String],
  )

  private object MultipartStartRequestPayload:
    def fromDomain(request: MultipartStartRequest): MultipartStartRequestPayload =
      MultipartStartRequestPayload(
        request.fileName,
        request.totalSize,
        request.mediaType,
        request.metadata,
        request.preferredChunkSize,
        request.partChecksums,
        request.wholeFileChecksum,
      )

  private final case class MultipartSessionPayload(
    uploadId: String,
    chunkSize: Int,
    maxChunks: Int,
    expiresAtEpochSeconds: Option[Long],
  )

  private object MultipartSessionPayload:
    def apply(session: MultipartSession): MultipartSessionPayload =
      new MultipartSessionPayload(session.uploadId, session.chunkSize, session.maxChunks, session.expiresAtEpochSeconds)

  private
  extension (payload: MultipartSessionPayload)
    def toDomain: MultipartSession =
      MultipartSession(payload.uploadId, payload.chunkSize, payload.maxChunks, payload.expiresAtEpochSeconds)

  private final case class PartAckPayload(
    partNumber: Int,
    acknowledgedSequence: Int,
    receivedBytes: Long,
  )

  private object PartAckPayload:
    def apply(ack: PartAck): PartAckPayload = PartAckPayload(ack.partNumber, ack.acknowledgedSequence, ack.receivedBytes)

  private
  extension (payload: PartAckPayload)
    def toDomain: PartAck = PartAck(payload.partNumber, payload.acknowledgedSequence, payload.receivedBytes)

  private final case class CompletionRequestPayload(
    parts: List[UploadedPartPayload],
    expectedChecksum: Option[String],
  )

  private object CompletionRequestPayload:
    def fromDomain(request: CompletionRequest): CompletionRequestPayload =
      CompletionRequestPayload(request.parts.map(UploadedPartPayload.fromDomain).toList, request.expectedChecksum)

  private final case class UploadedPartPayload(
    partNumber: Int,
    offset: Long,
    size: Long,
    checksum: Option[String],
  )

  private object UploadedPartPayload:
    def fromDomain(part: UploadedPart): UploadedPartPayload =
      UploadedPartPayload(part.partNumber, part.offset, part.size, part.checksum)

  private final case class CompletionResultPayload(
    documentId: String,
    attributes: Map[String, String],
  )

  private
  extension (payload: CompletionResultPayload)
    def toDomain: CompletionResult = CompletionResult(DocumentId(payload.documentId), payload.attributes)

object UploadNodeHttpClient:
  def live(
    baseUrl: URL,
    uploadsPrefix: Path,
    defaultHeaders: Headers = Headers.empty,
  ): ZLayer[Client, Nothing, UploadNodeHttpClient] =
    ZLayer.fromFunction {
      client: Client =>
        new UploadNodeHttpClient(baseUrl, uploadsPrefix, client.request(_), defaultHeaders)
    }
