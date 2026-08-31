package graviton.backend.s3

import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, DefaultCredentialsProvider, StaticCredentialsProvider}
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.checksums.{RequestChecksumCalculation, ResponseChecksumValidation}
import software.amazon.awssdk.core.metrics.CoreMetric
import software.amazon.awssdk.core.retry.RetryMode
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.metrics.{MetricCollection, MetricPublisher}
import software.amazon.awssdk.services.s3.{S3Configuration, S3Client}
import zio.{Runtime, Task, Unsafe, ZIO, ZLayer}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

object S3ClientLayer:

  def make(config: S3Config): Task[S3Client] =
    make(config, MetricsRegistry.noop)

  def make(config: S3Config, metrics: MetricsRegistry): Task[S3Client] =
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
              .addMetricPublisher(new S3MetricPublisher(metrics))
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

  private[s3] final class S3MetricPublisher(metrics: MetricsRegistry) extends MetricPublisher:
    override def publish(collection: MetricCollection): Unit =
      val operation  = collection.metricValues(CoreMetric.OPERATION_NAME).asScala.lastOption.getOrElse("unknown")
      val successful = collection.metricValues(CoreMetric.API_CALL_SUCCESSFUL).asScala.lastOption
      val retries    = collection.metricValues(CoreMetric.RETRY_COUNT).asScala.map(_.toLong).sum
      val duration   = collection
        .metricValues(CoreMetric.API_CALL_DURATION)
        .asScala
        .lastOption
        .map(value => value.toNanos.toDouble / 1e9)
      val tags       = Map(
        "operation" -> operation,
        "outcome"   -> successful.fold("unknown")(if _ then "success" else "failure"),
      )
      try
        Unsafe.unsafe { implicit unsafe =>
          Runtime.default.unsafe
            .run(
              metrics.counter(MetricKeys.S3ApiCallsTotal, tags) *>
                metrics.counterBy(MetricKeys.S3RetriesTotal, retries, tags) *>
                ZIO.foreachDiscard(duration)(metrics.histogram(MetricKeys.S3ApiCallDuration, _, tags))
            )
            .getOrThrowFiberFailure()
        }
      catch case NonFatal(_) => ()

    override def close(): Unit = ()
