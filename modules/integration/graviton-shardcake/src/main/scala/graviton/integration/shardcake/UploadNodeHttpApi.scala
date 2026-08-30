package graviton.integration.shardcake

import graviton.core.types.FileSize
import graviton.runtime.upload.*
import graviton.shared.MediaTypeText
import zio.*
import zio.http.*
import zio.stream.ZStream

final case class UploadNodeHttpApi(
  token: ShardcakeInternalToken,
  ingest: UploadNodeIngest,
):
  private sealed trait RequestError extends Exception

  private object RequestError:
    final case class InvalidInput(reason: String) extends Exception(reason) with RequestError

  private val uploadHandler: Handler[Any, Nothing, (String, String, Request), Response] =
    Handler.fromFunctionZIO[(String, String, Request)] { case (tenantText, sessionText, request) =>
      if !request.rawHeader(ShardcakeInternalAuth.HeaderName).exists(ShardcakeInternalAuth.matches(_, token)) then
        ZIO.succeed(Response.status(Status.Unauthorized))
      else
        (for
          tenant       <- ZIO.fromEither(TenantId.either(tenantText).left.map(RequestError.InvalidInput.apply))
          session      <- ZIO.fromEither(UploadSessionId.either(sessionText).left.map(RequestError.InvalidInput.apply))
          mediaType    <- ZIO.fromEither(
                            request.rawHeader("content-type") match
                              case None        => Left(RequestError.InvalidInput("Content-Type is required"))
                              case Some(value) => MediaTypeText.parse(value).left.map(RequestError.InvalidInput.apply)
                          )
          expectedSize <- ZIO.fromEither(parseContentLength(request).left.map(RequestError.InvalidInput.apply))
          result       <- ingest.uploadLocalSource(
                            UploadSessionKey(tenant, session),
                            UploadIntent(mediaType, expectedSize),
                            UploadSource.fromThrowable(request.body.asStream),
                          )
          encoded      <- ZIO.fromEither(UploadResultCodec.encode(result))
        yield Response(
          status = Status.Created,
          headers = Headers(Header.Custom("Content-Type", "application/msgpack")),
          body = Body.fromStream(ZStream.fromChunk(encoded), encoded.length.toLong),
        )).catchAll {
          case error: RequestError                         =>
            ZIO.logWarningCause("Owner-local upload input was rejected", Cause.fail(error)) *>
              ZIO.succeed(Response.status(Status.BadRequest))
          case error: UploadNodeIngest.Error.InvalidUpload =>
            ZIO.logWarningCause("Owner-local upload input was rejected", Cause.fail(error)) *>
              ZIO.succeed(Response.status(Status.BadRequest))
          case error                                       =>
            ZIO.logErrorCause("Owner-local upload failed", Cause.fail(error)) *>
              ZIO.succeed(Response.status(Status.InternalServerError))
        }
    }

  val routes: Routes[Any, Nothing] = Routes(
    Method.POST / "internal" / "graviton" / "uploads" / string("tenant") / string("session") -> uploadHandler
  )

  private def parseContentLength(request: Request): Either[String, Option[FileSize]] =
    request.rawHeader("content-length") match
      case None      => Right(None)
      case Some(raw) =>
        if raw.isEmpty || !raw.forall(character => character >= '0' && character <= '9') then
          Left("Content-Length must contain ASCII decimal digits")
        else
          for
            value <- raw.toLongOption.toRight("Content-Length exceeds a signed 64-bit integer")
            size  <- FileSize.either(value)
          yield Some(size)
