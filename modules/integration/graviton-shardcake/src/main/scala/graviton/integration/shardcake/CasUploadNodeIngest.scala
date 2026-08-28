package graviton.integration.shardcake

import graviton.core.attributes.BinaryAttributes
import graviton.pdf.PdfIngest
import graviton.runtime.model.BlobWritePlan
import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import graviton.runtime.stores.BlobStore
import graviton.runtime.upload.*
import zio.*
import zio.stream.ZStream

object CasUploadNodeIngest:
  val live: ZLayer[
    BlobStore & ShardcakeUploadConfig & UploadHotState & UploadSessionContext & MetricsRegistry,
    Nothing,
    UploadNodeIngest,
  ] =
    ZLayer.fromZIO {
      for
        store    <- ZIO.service[BlobStore]
        config   <- ZIO.service[ShardcakeUploadConfig]
        hotState <- ZIO.service[UploadHotState]
        context  <- ZIO.service[UploadSessionContext]
        metrics  <- ZIO.service[MetricsRegistry]
      yield Live(store, config.node, hotState, context, metrics)
    }

  private final case class Live(
    store: BlobStore,
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
      val ingest = for
        attributes <- ZIO.fromEither(
                        BinaryAttributes.empty
                          .advertiseMediaType(intent.contentType)
                          .left
                          .map(UploadNodeIngest.Error.InvalidUpload.apply)
                      )
        checked     = UploadByteStream.observeFrames(
                        UploadByteStream.enforceExpectedSize(bytes, intent.expectedSize),
                        key,
                        hotState,
                      )
        result     <-
          if PdfIngest.accepts(intent.contentType) then
            PdfIngest.put(store, intent.contentType, checked, BlobWritePlan(attributes = attributes))
          else checked.run(store.put(BlobWritePlan(attributes = attributes)))
      yield LocalizedUploadResult(result.key, result.stats, localNode)

      val recordHotStateSize = hotState.size.flatMap(size => metrics.gauge(MetricKeys.UploadHotStateEntries, size.toDouble, Map.empty))

      hotState.begin(key) *> recordHotStateSize *>
        context
          .locally(key)(ingest)
          .tapBoth(_ => hotState.fail(key), _ => hotState.complete(key))
          .mapError {
            case error: UploadNodeIngest.Error   => error
            case error: IllegalArgumentException => UploadNodeIngest.Error.InvalidUpload(error.getMessage)
            case cause                           => UploadNodeIngest.Error.StorageFailure(cause)
          }
          .ensuring(recordHotStateSize)
