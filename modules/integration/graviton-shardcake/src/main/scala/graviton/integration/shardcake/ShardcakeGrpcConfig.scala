package graviton.integration.shardcake

import com.devsisters.shardcake.GrpcConfig
import io.grpc.{Metadata, Status}
import scalapb.zio_grpc.{ZClientInterceptor, ZTransform}
import zio.*

object ShardcakeGrpcConfig:
  private val MaxControlMessageBytes = 64 * 1024
  private val TokenHeader            = Metadata.Key.of(
    ShardcakeInternalAuth.HeaderName,
    Metadata.ASCII_STRING_MARSHALLER,
  )

  sealed trait Error extends Throwable

  object Error:
    case object MissingInternalToken extends Error:
      override def getMessage: String = "Shardcake internal token is required"

  val live: ZLayer[ShardcakeUploadConfig, Error, GrpcConfig] =
    ZLayer.fromZIO {
      ZIO.service[ShardcakeUploadConfig].flatMap { config =>
        ZIO.fromOption(config.internalToken).orElseFail(Error.MissingInternalToken).map { token =>
          GrpcConfig.default.copy(
            maxInboundMessageSize = MaxControlMessageBytes,
            clientInterceptors = Seq(
              ZClientInterceptor.headersUpdater((_, _, metadata) => metadata.put(TokenHeader, token.value).unit)
            ),
            serverInterceptors = Seq(
              ZTransform { requestContext =>
                requestContext.metadata.get(TokenHeader).flatMap { provided =>
                  ZIO
                    .fail(Status.UNAUTHENTICATED.withDescription("invalid Shardcake node credentials").asException())
                    .unless(provided.exists(ShardcakeInternalAuth.matches(_, token)))
                    .as(requestContext)
                }
              }
            ),
          )
        }
      }
    }
