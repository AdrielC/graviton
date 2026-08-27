package graviton.integration.shardcake

import com.devsisters.shardcake.{Config, ShardManagerClient}
import sttp.client4.httpclient.zio.HttpClientZioBackend
import zio.*

import java.net.http.HttpRequest

object AuthenticatedShardManagerClient:
  val live: ZLayer[Config & ShardcakeUploadConfig, ShardcakeGrpcConfig.Error | Throwable, ShardManagerClient] =
    ZLayer.scoped {
      for
        config  <- ZIO.service[Config]
        upload  <- ZIO.service[ShardcakeUploadConfig]
        token   <- ZIO.fromOption(upload.internalToken).orElseFail(ShardcakeGrpcConfig.Error.MissingInternalToken)
        backend <- HttpClientZioBackend.scoped(customizeRequest = request =>
                     val builder = HttpRequest
                       .newBuilder(request.uri())
                       .method(request.method(), request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()))
                     request.headers().map().forEach { (key, values) =>
                       values.forEach { value =>
                         builder.header(key, value); ()
                       }
                     }
                     builder.header("Authorization", s"Bearer ${token.value}").build())
      yield ShardManagerClient.ShardManagerClientLive(backend, config)
    }
