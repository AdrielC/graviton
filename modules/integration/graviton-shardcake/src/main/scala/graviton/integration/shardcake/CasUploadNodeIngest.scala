package graviton.integration.shardcake

import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import graviton.runtime.stores.StoreError
import graviton.runtime.tenant.{TenantRoutingError, TenantStoreProvider}
import graviton.pdf.PdfUploadSupport
import graviton.runtime.upload.*
import zio.*
import zio.stream.ZStream

object CasUploadNodeIngest:
  private[shardcake] trait UploadIngestorResolver:
    def resolve(key: UploadSessionKey): IO[UploadNodeIngest.Error, UploadIngestor]

  private[shardcake] object UploadIngestorResolver:
    val fixed: URLayer[graviton.runtime.stores.BlobStore, UploadIngestorResolver] =
      ZLayer.fromFunction((store: graviton.runtime.stores.BlobStore) =>
        new UploadIngestorResolver:
          override def resolve(key: UploadSessionKey): IO[UploadNodeIngest.Error, UploadIngestor] =
            ZIO.succeed(PdfUploadSupport.ingestor(store))
      )

    val tenants: URLayer[TenantStoreProvider, UploadIngestorResolver] =
      ZLayer.fromFunction((provider: TenantStoreProvider) =>
        new UploadIngestorResolver:
          override def resolve(key: UploadSessionKey): IO[UploadNodeIngest.Error, UploadIngestor] =
            provider
              .resolve(key.tenantId)
              .map(binding => PdfUploadSupport.ingestor(binding.store))
              .mapError {
                case _: TenantRoutingError.UnknownTenant | _: TenantRoutingError.SuspendedTenant =>
                  UploadNodeIngest.Error.InvalidUpload("tenant storage is unavailable")
                case error                                                                       =>
                  UploadNodeIngest.Error.StorageFailure(error)
              }
      )

  val live: ZLayer[
    UploadIngestorResolver & ShardcakeUploadConfig & UploadHotState & UploadSessionContext & MetricsRegistry,
    Nothing,
    UploadNodeIngest,
  ] =
    ZLayer.fromZIO {
      for
        resolver <- ZIO.service[UploadIngestorResolver]
        config   <- ZIO.service[ShardcakeUploadConfig]
        hotState <- ZIO.service[UploadHotState]
        context  <- ZIO.service[UploadSessionContext]
        metrics  <- ZIO.service[MetricsRegistry]
      yield Live(resolver, config.node, hotState, context, metrics)
    }

  private final case class Live(
    resolver: UploadIngestorResolver,
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
      uploadLocalSource(key, intent, UploadSource.fromThrowable(bytes))

    override def uploadLocalSource(
      key: UploadSessionKey,
      intent: UploadIntent,
      source: UploadSource,
    ): IO[UploadNodeIngest.Error, LocalizedUploadResult] =
      val observed = UploadByteStream
        .observeFramesTyped(source.bytes, key, hotState)
        .mapError {
          case error: UploadSourceError      => error
          case error: UploadByteStream.Error => UploadSourceError.Rejected(error.getMessage, error)
        }
      val ingest   = resolver
        .resolve(key)
        .flatMap(
          _.putSource(intent, UploadSource.typed(observed))
            .map(result => LocalizedUploadResult(result.stored.key, result.stored.stats, localNode))
        )

      val recordHotStateSize = hotState.size.flatMap(size => metrics.gauge(MetricKeys.UploadHotStateEntries, size.toDouble, Map.empty))

      hotState.begin(key) *> recordHotStateSize *>
        context
          .locally(key)(ingest)
          .tapBoth(_ => hotState.fail(key), _ => hotState.complete(key))
          .mapError {
            case error: UploadNodeIngest.Error                                => error
            case invalid: UploadIngestor.Error.InvalidInput                   => UploadNodeIngest.Error.InvalidUpload(invalid.getMessage)
            case mismatch: UploadIngestor.Error.MediaTypeMismatch             => UploadNodeIngest.Error.InvalidUpload(mismatch.getMessage)
            case validation: UploadIngestor.Error.Validation                  => UploadNodeIngest.Error.InvalidUpload(validation.getMessage)
            case source: UploadIngestor.Error.SourceError                     => UploadNodeIngest.Error.SourceFailure(source.underlying)
            case UploadIngestor.Error.Storage(error: StoreError.InvalidInput) =>
              UploadNodeIngest.Error.InvalidUpload(error.reason)
            case cause                                                        => UploadNodeIngest.Error.StorageFailure(cause)
          }
          .ensuring(recordHotStateSize)
