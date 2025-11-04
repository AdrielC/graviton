package com.yourorg.graviton.client

import zio.*
import zio.http.*
import zio.json.*
import zio.stream.ZStream

final class GravitonUploadHttpClient(
  baseUrl: URL,
  uploadsPrefix: Path = Path.root / "api" / "uploads",
  defaultHeaders: Headers = Headers.empty,
)(transport: Request => Task[Response]) {

  import GravitonUploadHttpClient.*

  private val jsonHeaders: Headers = Headers(Header.ContentType(MediaType.application.json))

  def register(request: RegisterRequest): IO[Error, RegisterResponse] =
    val encoded = request.toJson
    execute(Method.POST, uploadsPrefix, Body.fromString(encoded), jsonHeaders)
      .flatMap(bodyAs[RegisterResponse])

  def uploadPart(sessionId: String, part: UploadPart, bytes: ZStream[Any, Throwable, Byte]): IO[Error, UploadAckPayload] =
    for {
      payload  <- bytes.runCollect.mapError(Error.TransportFailure.apply)
      target    = uploadsPrefix / sessionId / "parts" / part.sequence.toString
      headers   = part.contentLength match
                    case Some(length) => jsonHeaders ++ Headers(Header.Custom("Content-Length", length.toString))
                    case None         => jsonHeaders
      response <- execute(Method.PUT, target, Body.fromChunk(payload), headers)
      ack      <- bodyAs[UploadAckPayload](response)
    } yield ack

  def complete(sessionId: String, request: CompleteRequest): IO[Error, CompleteResponse] =
    execute(Method.POST, uploadsPrefix / sessionId / "complete", Body.fromString(request.toJson), jsonHeaders)
      .flatMap(bodyAs[CompleteResponse])

  def uploadOneShot(request: RegisterRequest, data: ZStream[Any, Throwable, Byte], checksum: Option[String]): IO[Error, CompleteResponse] =
    for {
      registered <- register(request)
      part        = UploadPart(sequence = 0L, offset = 0L, last = true, checksum = checksum, contentLength = request.totalSize)
      _          <- uploadPart(registered.sessionId, part, data)
      completed  <- complete(
                      registered.sessionId,
                      CompleteRequest(expectedObjectHash = checksum, manifestContentType = None, manifestBytes = None),
                    )
    } yield completed

  private def execute(method: Method, path: Path, body: Body, headers: Headers): IO[Error, Response] = {
    val url     = baseUrl.copy(path = baseUrl.path ++ path)
    val request = Request(
      method = method,
      url = url,
      headers = defaultHeaders ++ headers,
      body = body,
    )
    transport(request).mapError(Error.TransportFailure.apply).flatMap { response =>
      if response.status.isSuccess then ZIO.succeed(response)
      else
        response.body.asString.mapError(Error.TransportFailure.apply).flatMap { body =>
          ZIO.fail(Error.UnexpectedStatus(response.status, body))
        }
    }
  }

  private def bodyAs[A: JsonDecoder](response: Response): IO[Error, A] =
    response.body.asString
      .mapError(Error.TransportFailure.apply)
      .flatMap { text =>
        ZIO.fromEither(text.fromJson[A]).mapError(err => Error.DecodingFailure(response.status, text, err))
      }
}

object GravitonUploadHttpClient {

  final case class RegisterRequest(
    objectContentType: String,
    totalSize: Option[Long],
    metadata: Chunk[MetadataNamespacePayload],
    clientSessionId: Option[String],
  ) derives JsonEncoder

  final case class RegisterResponse(sessionId: String, ttlSeconds: Long) derives JsonDecoder

  final case class UploadPart(
    sequence: Long,
    offset: Long,
    last: Boolean,
    checksum: Option[String],
    contentLength: Option[Long],
  )

  final case class UploadAckPayload(sessionId: String, acknowledgedSequence: Long, receivedBytes: Long) derives JsonDecoder

  final case class CompleteRequest(
    expectedObjectHash: Option[String],
    manifestContentType: Option[String],
    manifestBytes: Option[String],
  ) derives JsonEncoder

  final case class CompleteResponse(
    documentId: String,
    blobHash: String,
    objectContentType: String,
    finalUrl: Option[String],
  ) derives JsonDecoder

  final case class MetadataNamespacePayload(
    namespace: String,
    schemaContentType: Option[String],
    schemaBytes: Option[String],
    dataContentType: String,
    dataBytes: String,
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

    case object EncodingFailure extends Error {
      override def message: String = "Failed to encode JSON payload"
    }
  }
}
