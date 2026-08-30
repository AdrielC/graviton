package graviton.integration.shardcake

import graviton.runtime.upload.*
import graviton.shared.MediaTypeText
import graviton.streams.BoundedByteStream
import zio.*
import zio.http.*
import zio.stream.ZStream

object ZioHttpUploadNodeTransport:
  val live: ZLayer[Client & ShardcakeUploadConfig, ShardcakeGrpcConfig.Error, UploadNodeTransport] =
    ZLayer.fromZIO {
      for
        client <- ZIO.service[Client]
        config <- ZIO.service[ShardcakeUploadConfig]
        token  <- ZIO.fromOption(config.internalToken).orElseFail(ShardcakeGrpcConfig.Error.MissingInternalToken)
      yield Live(client, token)
    }

  private final case class Live(
    client: Client,
    token: ShardcakeInternalToken,
  ) extends UploadNodeTransport:
    override def upload(
      owner: UploadNode,
      key: UploadSessionKey,
      intent: UploadIntent,
      bytes: ZStream[Any, Throwable, Byte],
    ): IO[UploadNodeTransport.Error, LocalizedUploadResult] =
      uploadSource(owner, key, intent, UploadSource.fromThrowable(bytes))

    override def uploadSource(
      owner: UploadNode,
      key: UploadSessionKey,
      intent: UploadIntent,
      source: UploadSource,
    ): IO[UploadNodeTransport.Error, LocalizedUploadResult] =
      val endpoint =
        s"http://${owner.host.value}:${owner.uploadPort.value}/internal/graviton/uploads/${key.tenantId.value}/${key.uploadSessionId.value}"

      (for
        url         <- ZIO.fromEither(URL.decode(endpoint)).mapError(new IllegalArgumentException(_))
        contentType <- ZIO.fromEither(MediaTypeText.renderEither(intent.contentType)).mapError(new IllegalArgumentException(_))
        bodyStream   = source.bytes.mapError(UploadNodeTransport.Error.SourceFailure(owner, _))
        body         = intent.expectedSize match
                         case Some(length) => Body.fromStream(bodyStream, length.value)
                         case None         => Body.fromStreamChunked(bodyStream)
        request      = Request(
                         method = Method.POST,
                         url = url,
                         headers = Headers(
                           Header.Custom(ShardcakeInternalAuth.HeaderName, token.value),
                           Header.Custom("Content-Type", contentType),
                         ),
                         body = body,
                       )
        result      <- ZIO.scoped {
                         client(request).flatMap { response =>
                           if response.status == Status.Created then
                             BoundedByteStream
                               .collectControlPlane(response.body.asStream)
                               .flatMap(chunk => ZIO.fromEither(UploadResultCodec.decode(chunk)))
                               .flatMap(result =>
                                 ZIO
                                   .fail(UploadNodeTransport.Error.InvalidResponse(owner, "response owner does not match the selected node"))
                                   .unless(result.owner == owner)
                                   .as(result)
                               )
                           else
                             ZIO.fail(
                               UploadNodeTransport.Error.Rejected(
                                 owner,
                                 response.status.code,
                                 s"owner_${response.status.code}",
                               )
                             )
                         }
                       }
      yield result).mapError {
        case error: UploadNodeTransport.Error => error
        case error: UploadResultCodec.Error   => UploadNodeTransport.Error.InvalidResponse(owner, error.getMessage)
        case error                            => UploadNodeTransport.Error.ConnectionFailure(owner, error)
      }
