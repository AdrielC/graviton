package graviton.integration.shardcake

import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import graviton.runtime.upload.*
import zio.*
import zio.stream.ZStream

object CasUploadNodeIngest:
  val live: ZLayer[
    UploadIngestor & ShardcakeUploadConfig & UploadHotState & UploadSessionContext & MetricsRegistry,
    Nothing,
    UploadNodeIngest,
  ] =
    ZLayer.fromZIO {
      for
        ingestor <- ZIO.service[UploadIngestor]
        config   <- ZIO.service[ShardcakeUploadConfig]
        hotState <- ZIO.service[UploadHotState]
        context  <- ZIO.service[UploadSessionContext]
        metrics  <- ZIO.service[MetricsRegistry]
      yield Live(ingestor, config.node, hotState, context, metrics)
    }

  private final case class Live(
    ingestor: UploadIngestor,
    localNode: UploadNode,
    hotState: UploadHotState,
    context: UploadSessionContext,
    metrics: MetricsRegistry,
  ) extends UploadNodeIngest:
    override def uploadLocal(
      key: UploadSessionKey,
      intent: UploadIntent,
      bytes: ZStream[Any, Throwable, Byte],
    ): IO[UploadNodeIngest.Error, LocalizedUploadResult] =
      val observed = UploadByteStream.observeFrames(bytes, key, hotState)
      val ingest   = ingestor
        .put(intent, observed)
        .map(result => LocalizedUploadResult(result.stored.key, result.stored.stats, localNode))

      val hotStateTags       = Map("node" -> localNode.id.value)
      val recordHotStateSize = hotState.size.flatMap(size => metrics.gauge(MetricKeys.UploadHotStateEntries, size.toDouble, hotStateTags))

      hotState.begin(key) *> recordHotStateSize *>
        context
          .locally(key)(ingest)
          .tapBoth(_ => hotState.fail(key), _ => hotState.complete(key))
          .mapError {
            case invalid: UploadIngestor.Error.InvalidInput                    => UploadNodeIngest.Error.InvalidUpload(invalid.getMessage)
            case mismatch: UploadIngestor.Error.MediaTypeMismatch              => UploadNodeIngest.Error.InvalidUpload(mismatch.getMessage)
            case validation: UploadIngestor.Error.Validation                   => UploadNodeIngest.Error.InvalidUpload(validation.getMessage)
            case UploadIngestor.Error.Storage(error: IllegalArgumentException) =>
              UploadNodeIngest.Error.InvalidUpload(error.getMessage)
            case cause                                                         => UploadNodeIngest.Error.StorageFailure(cause)
          }
          .ensuring(recordHotStateSize)
