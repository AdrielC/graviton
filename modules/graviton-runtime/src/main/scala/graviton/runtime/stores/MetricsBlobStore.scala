package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import graviton.runtime.model.{BlobStat, BlobWritePlan, BlobWriteResult}
import zio.*
import zio.stream.*

/**
 * Decorating wrapper that records metrics for every BlobStore operation.
 *
 * Wraps an existing `BlobStore` and emits counters/gauges via `MetricsRegistry`
 * for put, get, stat, and delete operations. Useful for Prometheus-based
 * monitoring of ingest throughput and retrieval latency.
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
    val tags = baseTags + ("op" -> "put")
    underlying.put(plan).mapZIO { result =>
      for
        _ <- metrics.counter("graviton.blob.put.count", tags)
        _ <- metrics.gauge("graviton.blob.put.bytes", result.stats.totalBytes.toDouble, tags)
        _ <- metrics.gauge("graviton.blob.put.blocks", result.stats.blockCount.toDouble, tags)
        _ <- metrics.gauge("graviton.blob.put.fresh_blocks", result.stats.freshBlocks.toDouble, tags)
        _ <- metrics.gauge("graviton.blob.put.dup_blocks", result.stats.duplicateBlocks.toDouble, tags)
        _ <- metrics.gauge("graviton.blob.put.duration_seconds", result.stats.durationSeconds, tags)
      yield result
    }

  override def get(key: BinaryKey): ZStream[Any, Throwable, Byte] =
    val tags = baseTags + ("op" -> "get")
    ZStream.fromZIO(metrics.counter("graviton.blob.get.count", tags)).drain ++
      underlying.get(key)

  override def stat(key: BinaryKey): ZIO[Any, Throwable, Option[BlobStat]] =
    val tags = baseTags + ("op" -> "stat")
    metrics.counter("graviton.blob.stat.count", tags) *>
      underlying.stat(key)

  override def delete(key: BinaryKey): ZIO[Any, Throwable, Unit] =
    val tags = baseTags + ("op" -> "delete")
    metrics.counter("graviton.blob.delete.count", tags) *>
      underlying.delete(key)

object MetricsBlobStore:

  def apply(
    underlying: BlobStore,
    metrics: MetricsRegistry,
    tags: Map[String, String] = Map.empty,
  ): MetricsBlobStore =
    new MetricsBlobStore(underlying, metrics, tags)

  val layer: ZLayer[BlobStore & MetricsRegistry, Nothing, BlobStore] =
    ZLayer.fromFunction((store: BlobStore, reg: MetricsRegistry) => new MetricsBlobStore(store, reg): BlobStore)
