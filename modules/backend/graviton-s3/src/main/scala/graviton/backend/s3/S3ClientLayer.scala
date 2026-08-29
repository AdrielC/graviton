package graviton.backend.s3

import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, DefaultCredentialsProvider, StaticCredentialsProvider}
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.checksums.{RequestChecksumCalculation, ResponseChecksumValidation}
import software.amazon.awssdk.core.retry.RetryMode
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.services.s3.{S3Configuration, S3Client}
import zio.{Task, ZIO, ZLayer}

object S3ClientLayer:

  def make(config: S3Config): Task[S3Client] =
    ZIO.attempt {
      val builder =
        S3Client
          .builder()
          .region(config.region)
          .httpClientBuilder(
            ApacheHttpClient
              .builder()
              .connectionTimeout(java.time.Duration.ofSeconds(5))
              .socketTimeout(java.time.Duration.ofSeconds(30))
          )
          .overrideConfiguration(
            ClientOverrideConfiguration
              .builder()
              .apiCallAttemptTimeout(java.time.Duration.ofSeconds(15))
              .apiCallTimeout(java.time.Duration.ofSeconds(45))
              .retryStrategy(RetryMode.STANDARD)
              .build()
          )
          .requestChecksumCalculation(RequestChecksumCalculation.WHEN_SUPPORTED)
          .responseChecksumValidation(ResponseChecksumValidation.WHEN_SUPPORTED)
          .serviceConfiguration(
            S3Configuration
              .builder()
              .pathStyleAccessEnabled(config.forcePathStyle)
              .build()
          )

      val withCredentials = (config.accessKeyId, config.secretAccessKey) match
        case (Some(accessKey), Some(secretKey)) =>
          builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        case (None, None)                       =>
          builder.credentialsProvider(DefaultCredentialsProvider.builder().build())
        case _                                  =>
          throw new IllegalArgumentException("S3 access key and secret key must be configured together")

      val withEndpoint =
        config.endpointOverride match
          case Some(uri) => withCredentials.endpointOverride(uri)
          case None      => withCredentials

      withEndpoint.build()
    }

  def layer(config: S3Config): ZLayer[Any, Throwable, S3Client] =
    ZLayer.fromZIO(make(config))
