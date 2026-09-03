package graviton.backend.s3

import graviton.runtime.lifecycle.ResourceFinalizer
import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import graviton.runtime.stores.{BackendInitError, StoreBackend}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, DefaultCredentialsProvider, StaticCredentialsProvider}
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.checksums.{RequestChecksumCalculation, ResponseChecksumValidation}
import software.amazon.awssdk.core.metrics.CoreMetric
import software.amazon.awssdk.core.retry.RetryMode
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.metrics.{MetricCollection, MetricPublisher}
import software.amazon.awssdk.services.s3.{S3Configuration, S3Client}
import zio.{IO, Runtime, Scope, Task, UIO, Unsafe, ZIO, ZLayer}

import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

object S3ClientLayer:

  def makeTyped(config: S3Config): IO[BackendInitError, S3Client] =
    build(config, None)

  @deprecated("Use scoped(config, metrics) so the metrics worker and client share a Scope", "0.9.0")
  def makeTyped(config: S3Config, metrics: MetricsRegistry): IO[BackendInitError, S3Client] =
    build(config, Some(new LegacyS3MetricPublisher(metrics)))

  private def build(config: S3Config, publisher: Option[MetricPublisher]): IO[BackendInitError, S3Client] =
    ZIO
      .attempt {
        val overrideBuilder =
          ClientOverrideConfiguration
            .builder()
            .apiCallAttemptTimeout(java.time.Duration.ofSeconds(15))
            .apiCallTimeout(java.time.Duration.ofSeconds(45))
            .retryStrategy(RetryMode.STANDARD)
        publisher.foreach(overrideBuilder.addMetricPublisher)

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
            .overrideConfiguration(overrideBuilder.build())
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
      .mapError(BackendInitError.fromThrowable(StoreBackend.S3))

  def scoped(config: S3Config): ZIO[Scope, BackendInitError, S3Client] =
    ZIO.acquireRelease(makeTyped(config))(client => ResourceFinalizer.closeBlocking("S3 client")(client.close()))

  def scoped(config: S3Config, metrics: MetricsRegistry): ZIO[Scope, BackendInitError, S3Client] =
    for
      publisher <- S3MetricPublisher.scoped(metrics)
      client    <- ZIO.acquireRelease(build(config, Some(publisher)))(client => ResourceFinalizer.closeBlocking("S3 client")(client.close()))
    yield client

  def typedLayer(config: S3Config): ZLayer[Any, BackendInitError, S3Client] =
    ZLayer.scoped(scoped(config))

  @deprecated("Use makeTyped to preserve the backend initialization error ADT", "0.9.0")
  def make(config: S3Config): Task[S3Client] = makeTyped(config)

  @deprecated("Use scoped with MetricsRegistry so the publisher and client share a Scope", "0.9.0")
  def make(config: S3Config, metrics: MetricsRegistry): Task[S3Client] = makeTyped(config, metrics)

  @deprecated("Use typedLayer to preserve the backend initialization error ADT", "0.9.0")
  def layer(config: S3Config): ZLayer[Any, Throwable, S3Client] = typedLayer(config)

  private[s3] final class S3MetricPublisher private (
    metrics: MetricsRegistry,
    queue: ArrayBlockingQueue[S3MetricPublisher.Event],
    dropped: AtomicLong,
  ) extends MetricPublisher:
    override def publish(collection: MetricCollection): Unit =
      val operation  = collection.metricValues(CoreMetric.OPERATION_NAME).asScala.lastOption.getOrElse("unknown")
      val successful = collection.metricValues(CoreMetric.API_CALL_SUCCESSFUL).asScala.lastOption
      val retries    = collection.metricValues(CoreMetric.RETRY_COUNT).asScala.map(_.toLong).sum
      val duration   = collection
        .metricValues(CoreMetric.API_CALL_DURATION)
        .asScala
        .lastOption
        .map(value => value.toNanos.toDouble / 1e9)
      val outcome    = successful.fold("unknown")(if _ then "success" else "failure")
      if !queue.offer(S3MetricPublisher.Event(operation, outcome, retries, duration)) then
        val _ = dropped.incrementAndGet()

    override def close(): Unit = ()

    private[s3] def run: UIO[Nothing] =
      ZIO
        .attemptBlockingInterrupt(Option(queue.poll(1L, TimeUnit.SECONDS)))
        .orDie
        .flatMap { event =>
          val droppedCount = dropped.getAndSet(0L)
          metrics.counterBy(MetricKeys.S3MetricEventsDroppedTotal, droppedCount, Map.empty) *>
            ZIO.foreachDiscard(event)(record)
        }
        .forever

    private def record(event: S3MetricPublisher.Event): UIO[Unit] =
      val tags = Map("operation" -> event.operation, "outcome" -> event.outcome)
      metrics.counter(MetricKeys.S3ApiCallsTotal, tags) *>
        metrics.counterBy(MetricKeys.S3RetriesTotal, event.retries, tags) *>
        ZIO.foreachDiscard(event.durationSeconds)(metrics.histogram(MetricKeys.S3ApiCallDuration, _, tags))

  private[s3] object S3MetricPublisher:
    private val DefaultCapacity = 1024

    final case class Event(
      operation: String,
      outcome: String,
      retries: Long,
      durationSeconds: Option[Double],
    )

    def scoped(metrics: MetricsRegistry, capacity: Int = DefaultCapacity): ZIO[Scope, Nothing, S3MetricPublisher] =
      for
        queue    <- ZIO.succeed(new ArrayBlockingQueue[Event](math.max(1, capacity)))
        dropped  <- ZIO.succeed(new AtomicLong(0L))
        publisher = new S3MetricPublisher(metrics, queue, dropped)
        _        <- publisher.run.forkScoped
      yield publisher

  /**
   * Binary-compatibility bridge for the deprecated unscoped constructor.
   *
   * New code uses [[S3MetricPublisher.scoped]], whose bounded worker is owned by
   * the caller's ZIO Scope. This bridge deliberately remains private and exists
   * only so clients compiled against 0.8.x continue to link.
   */
  private final class LegacyS3MetricPublisher(metrics: MetricsRegistry) extends MetricPublisher:
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
