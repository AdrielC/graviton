package graviton.runtime.upload

import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import zio.*
import zio.stream.ZStream

/** Routes one non-replayable byte stream exactly once to its Shardcake owner. */
trait LocalityAwareUpload:
  def upload(
    key: UploadSessionKey,
    intent: UploadIntent,
    bytes: ZStream[Any, Throwable, Byte],
  ): IO[LocalityAwareUpload.Error, LocalizedUploadResult]

  def uploadSource(
    key: UploadSessionKey,
    intent: UploadIntent,
    source: UploadSource,
  ): IO[LocalityAwareUpload.Error, LocalizedUploadResult] =
    upload(key, intent, source.bytes.mapError(error => error: Throwable))

object LocalityAwareUpload:
  sealed trait Error extends Throwable

  object Error:
    final case class Placement(cause: UploadPlacement.Error) extends Error:
      override def getMessage: String  = cause.getMessage
      override def getCause: Throwable = cause

    final case class LocalIngest(cause: UploadNodeIngest.Error) extends Error:
      override def getMessage: String  = cause.getMessage
      override def getCause: Throwable = cause

    final case class RemoteTransport(cause: UploadNodeTransport.Error) extends Error:
      override def getMessage: String  = cause.getMessage
      override def getCause: Throwable = cause

  val service: ZIO[LocalityAwareUpload, Nothing, LocalityAwareUpload] = ZIO.service[LocalityAwareUpload]

  def upload(
    key: UploadSessionKey,
    intent: UploadIntent,
    bytes: ZStream[Any, Throwable, Byte],
  ): ZIO[LocalityAwareUpload, Error, LocalizedUploadResult] =
    ZIO.serviceWithZIO[LocalityAwareUpload](_.upload(key, intent, bytes))

  def uploadSource(
    key: UploadSessionKey,
    intent: UploadIntent,
    source: UploadSource,
  ): ZIO[LocalityAwareUpload, Error, LocalizedUploadResult] =
    ZIO.serviceWithZIO[LocalityAwareUpload](_.uploadSource(key, intent, source))

  val live: ZLayer[UploadPlacement & UploadNodeIngest & UploadNodeTransport, Nothing, LocalityAwareUpload] =
    layer(MetricsRegistry.noop)

  val instrumented: ZLayer[
    UploadPlacement & UploadNodeIngest & UploadNodeTransport & MetricsRegistry,
    Nothing,
    LocalityAwareUpload,
  ] =
    ZLayer.fromZIO(ZIO.service[MetricsRegistry]).flatMap(environment => layer(environment.get[MetricsRegistry]))

  private def layer(
    metrics: MetricsRegistry
  ): ZLayer[UploadPlacement & UploadNodeIngest & UploadNodeTransport, Nothing, LocalityAwareUpload] =
    ZLayer.fromZIO {
      for
        placement <- ZIO.service[UploadPlacement]
        ingest    <- ZIO.service[UploadNodeIngest]
        transport <- ZIO.service[UploadNodeTransport]
      yield new LocalityAwareUpload:
        override def upload(
          key: UploadSessionKey,
          intent: UploadIntent,
          bytes: ZStream[Any, Throwable, Byte],
        ): IO[Error, LocalizedUploadResult] =
          uploadSource(key, intent, UploadSource.fromThrowable(bytes))

        override def uploadSource(
          key: UploadSessionKey,
          intent: UploadIntent,
          source: UploadSource,
        ): IO[Error, LocalizedUploadResult] =
          for
            owner  <- placement
                        .locate(key)
                        .tapError(_ => metrics.counter(MetricKeys.UploadLocalityFailuresTotal, Map("stage" -> "placement")))
                        .mapError(Error.Placement.apply)
            local  <- placement.localNode
            route   = if owner == local then "local" else "remote"
            tags    = Map("route" -> route)
            _      <- metrics.counter(MetricKeys.UploadLocalityDecisionsTotal, tags)
            result <- ZIO.logAnnotate(
                        Set(
                          LogAnnotation("component", "upload"),
                          LogAnnotation("operation", "ingest"),
                          LogAnnotation("tenant_id", key.tenantId.value),
                          LogAnnotation("session_id", key.uploadSessionId.value),
                          LogAnnotation("owner_node", owner.id.value),
                          LogAnnotation("route", route),
                        )
                      ) {
                        (if owner == local then ingest.uploadLocalSource(key, intent, source).mapError(Error.LocalIngest.apply)
                         else transport.uploadSource(owner, key, intent, source).mapError(Error.RemoteTransport.apply))
                          .tapError(error =>
                            metrics.counter(MetricKeys.UploadLocalityFailuresTotal, tags) *>
                              ZIO.logWarningCause("Upload ingest failed", Cause.fail(error))
                          )
                          .tap(result =>
                            ZIO.logInfo(
                              s"Upload ingest completed: bytes=${result.stats.totalBytes} blocks=${result.stats.blockCount} " +
                                s"fresh=${result.stats.freshBlocks} duplicate=${result.stats.duplicateBlocks}"
                            )
                          )
                      }
          yield result
    }
