package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.core.types.{BlobOffset, FileSize}
import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import graviton.runtime.model.{BlobDescription, BlobListing, BlobStat, BlobWritePlan}
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
        .mapErrorZIO(error => metrics.counter(MetricKeys.BlobOperationFailuresTotal, tags).as(error))
        .ensuring(recordDuration(tags, started))
    }

  override def get(key: BinaryKey.Blob): ZStream[Any, Throwable, Byte] =
    instrumentStream("get")(underlying.get(key))

  override def getRange(
    key: BinaryKey.Blob,
    start: BlobOffset,
    length: FileSize,
  ): ZStream[Any, Throwable, Byte] =
    instrumentStream("get_range")(underlying.getRange(key, start, length))

  override def stat(key: BinaryKey.Blob): ZIO[Any, Throwable, Option[BlobStat]] =
    instrument("stat")(underlying.stat(key))

  override def list: ZIO[Any, Throwable, Chunk[BlobListing]] =
    instrument("list")(underlying.list)

  override def inspect(key: BinaryKey.Blob): ZIO[Any, Throwable, Option[BlobDescription]] =
    instrument("inspect")(underlying.inspect(key))

  override def delete(key: BinaryKey.Blob): ZIO[Any, Throwable, Unit] =
    instrument("delete")(underlying.delete(key))

  override def healthCheck: ZIO[Any, Throwable, Unit] =
    instrument("health_check")(underlying.healthCheck)

  private def instrument[A](operation: String)(effect: Task[A]): Task[A] =
    val tags = operationTags(operation)
    for
      started <- Clock.nanoTime
      result  <- (metrics.counter(MetricKeys.BlobOperationsTotal, tags) *> effect)
                   .tapError(_ => metrics.counter(MetricKeys.BlobOperationFailuresTotal, tags))
                   .ensuring(recordDuration(tags, started))
    yield result

  private def instrumentStream(operation: String)(stream: ZStream[Any, Throwable, Byte]): ZStream[Any, Throwable, Byte] =
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
