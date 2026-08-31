package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.core.types.{BlobOffset, FileSize}
import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import graviton.runtime.model.{
  BlobBlockDescription,
  BlobDescription,
  BlobInspectionPage,
  BlobListing,
  BlobStat,
  BlobWritePlan,
  InventoryCursor,
  InventoryPage,
  InventoryPageSize,
}
import zio.*
import zio.stream.*

/**
 * Decorating wrapper that records metrics for every BlobStore operation.
 *
 * Wraps an existing `BlobStore` and emits bounded-cardinality counters and
 * duration histograms for every lifecycle operation.
 *
 * Usage:
 * {{{
 * val metered = MetricsBlobStore(underlying, registry, Map("env" -> "prod"))
 * }}}
 */
final class MetricsBlobStore(
  underlying: BlobStore,
  metrics: MetricsRegistry,
  baseTags: Map[String, String] = Map.empty,
) extends BlobStore:

  override def put(plan: BlobWritePlan = BlobWritePlan()): BlobSink =
    val tags = operationTags("put")
    ZSink.unwrap {
      for
        started <- Clock.nanoTime
        _       <- metrics.counter(MetricKeys.BlobOperationsTotal, tags)
      yield underlying
        .put(plan)
        .mapErrorZIO { error =>
          metrics.counter(MetricKeys.BlobOperationFailuresTotal, tags) *>
            (error match
              case _: StoreError.TenantStorageQuotaExceeded =>
                metrics.counter(MetricKeys.TenantQuotaRejectionsTotal, Map("quota" -> "retained_bytes"))
              case _: StoreError.CapacityExceeded           =>
                metrics.counter(MetricKeys.TenantQuotaRejectionsTotal, Map("quota" -> "object_bytes"))
              case _                                        => ZIO.unit
            ).as(error)
        }
        .ensuring(recordDuration(tags, started))
    }

  override def get(key: BinaryKey.Blob): ZStream[Any, StoreError, Byte] =
    instrumentStream("get")(underlying.get(key))

  override def getRange(
    key: BinaryKey.Blob,
    start: BlobOffset,
    length: FileSize,
  ): ZStream[Any, StoreError, Byte] =
    instrumentStream("get_range")(underlying.getRange(key, start, length))

  override def stat(key: BinaryKey.Blob): IO[StoreError, Option[BlobStat]] =
    instrument("stat")(underlying.stat(key))

  override def inventoryPage(after: Option[InventoryCursor], limit: InventoryPageSize): IO[StoreError, InventoryPage[BlobListing]] =
    instrument("inventory")(underlying.inventoryPage(after, limit))

  override def inspect(key: BinaryKey.Blob): IO[StoreError, Option[BlobDescription]] =
    instrument("inspect")(underlying.inspect(key))

  override def streamBlockDescriptions(key: BinaryKey.Blob): ZStream[Any, StoreError, BlobBlockDescription] =
    instrumentStream("inspect_stream")(underlying.streamBlockDescriptions(key))

  override def inspectPage(
    key: BinaryKey.Blob,
    after: Option[InventoryCursor],
    limit: InventoryPageSize,
  ): IO[StoreError, Option[BlobInspectionPage]] =
    instrument("inspect_page")(underlying.inspectPage(key, after, limit))

  override def metadata(key: BinaryKey.Blob): IO[StoreError, Option[BlobMetadataV1]] =
    instrument("metadata")(underlying.metadata(key))

  override def delete(key: BinaryKey.Blob): IO[StoreError, Unit] =
    instrument("delete")(underlying.delete(key))

  override def healthCheck: IO[StoreError, Unit] =
    instrument("health_check")(underlying.healthCheck)

  private def instrument[A](operation: String)(effect: IO[StoreError, A]): IO[StoreError, A] =
    val tags = operationTags(operation)
    for
      started <- Clock.nanoTime
      result  <- (metrics.counter(MetricKeys.BlobOperationsTotal, tags) *> effect)
                   .tapError(_ => metrics.counter(MetricKeys.BlobOperationFailuresTotal, tags))
                   .ensuring(recordDuration(tags, started))
    yield result

  private def instrumentStream[A](operation: String)(stream: ZStream[Any, StoreError, A]): ZStream[Any, StoreError, A] =
    val tags = operationTags(operation)
    ZStream.unwrap {
      Clock.nanoTime.map { started =>
        ZStream.fromZIO(metrics.counter(MetricKeys.BlobOperationsTotal, tags)).drain ++
          stream
            .tapError(_ => metrics.counter(MetricKeys.BlobOperationFailuresTotal, tags))
            .ensuring(recordDuration(tags, started))
      }
    }

  private def recordDuration(tags: Map[String, String], started: Long): UIO[Unit] =
    Clock.nanoTime.flatMap(finished => metrics.histogram(MetricKeys.BlobOperationDuration, (finished - started).toDouble / 1e9, tags))

  private def operationTags(operation: String): Map[String, String] =
    baseTags + ("operation" -> operation)

object MetricsBlobStore:

  def apply(
    underlying: BlobStore,
    metrics: MetricsRegistry,
    tags: Map[String, String] = Map.empty,
  ): MetricsBlobStore =
    new MetricsBlobStore(underlying, metrics, tags)

  val layer: ZLayer[BlobStore & MetricsRegistry, Nothing, BlobStore] =
    ZLayer.fromFunction((store: BlobStore, reg: MetricsRegistry) => new MetricsBlobStore(store, reg): BlobStore)
