package graviton.backend.s3

import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.services.s3.{S3Configuration, S3Client}
import zio.{Task, ZIO, ZLayer}

object S3ClientLayer:

  def make(config: S3Config): Task[S3Client] =
    ZIO.attempt {
      val creds = AwsBasicCredentials.create(config.accessKeyId, config.secretAccessKey)

      val builder =
        S3Client
          .builder()
          .region(config.region)
          .credentialsProvider(StaticCredentialsProvider.create(creds))
          .serviceConfiguration(
            S3Configuration
              .builder()
              .pathStyleAccessEnabled(config.forcePathStyle)
              .build()
          )

      val withEndpoint =
        config.endpointOverride match
          case Some(uri) => builder.endpointOverride(uri)
          case None      => builder

      withEndpoint.build()
    }

  def layer(config: S3Config): ZLayer[Any, Throwable, S3Client] =
    ZLayer.fromZIO(make(config))
