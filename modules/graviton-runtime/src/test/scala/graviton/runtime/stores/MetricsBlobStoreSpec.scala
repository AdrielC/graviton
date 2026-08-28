package graviton.runtime.stores

import graviton.runtime.metrics.{InMemoryMetricsRegistry, MetricKey, MetricKeys}
import zio.*
import zio.stream.ZStream
import zio.test.*

import java.nio.charset.StandardCharsets

object MetricsBlobStoreSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("MetricsBlobStore")(
      test("records put metrics after ingest") {
        val data = Chunk.fromArray("metrics-test".getBytes(StandardCharsets.UTF_8))
        for
          registry   <- InMemoryMetricsRegistry.make
          blockStore <- InMemoryBlockStore.make
          repo       <- InMemoryBlobManifestRepo.make
          underlying  = new CasBlobStore(blockStore, repo)
          metered     = MetricsBlobStore(underlying, registry, Map("env" -> "test"))

          _        <- ZStream.fromChunk(data).run(metered.put())
          snapshot <- registry.snapshot

          putCountKey = MetricKey(MetricKeys.BlobOperationsTotal, Map("env" -> "test", "operation" -> "put"))
          durationKey = MetricKey(MetricKeys.BlobOperationDuration, Map("env" -> "test", "operation" -> "put"))
        yield assertTrue(
          snapshot.counters.contains(putCountKey),
          snapshot.counters(putCountKey) == 1L,
          snapshot.gauges.contains(durationKey),
        )
      },
      test("records failed puts and their duration") {
        val failure = new java.io.IOException("block backend unavailable")
        val data    = Chunk.fromArray("fail-this-put".getBytes(StandardCharsets.UTF_8))
        for
          registry <- InMemoryMetricsRegistry.make
          delegate <- InMemoryBlockStore.make
          broken    = new BlockStore:
                        override def putBlocks(plan: graviton.runtime.model.BlockWritePlan) = delegate.putBlocks(plan)
                        override def putBlock(
                          block: graviton.runtime.model.CanonicalBlock,
                          plan: graviton.runtime.model.BlockWritePlan,
                        ): Task[graviton.runtime.model.StoredBlock] = ZIO.fail(failure)
                        override def get(key: graviton.core.keys.BinaryKey.Block)           = delegate.get(key)
                        override def exists(key: graviton.core.keys.BinaryKey.Block)        = delegate.exists(key)
          repo     <- InMemoryBlobManifestRepo.make
          metered   = MetricsBlobStore(new CasBlobStore(broken, repo), registry, Map("env" -> "test"))
          exit     <- ZStream.fromChunk(data).run(metered.put()).exit
          snapshot <- registry.snapshot

          tags        = Map("env" -> "test", "operation" -> "put")
          operation   = MetricKey(MetricKeys.BlobOperationsTotal, tags)
          failed      = MetricKey(MetricKeys.BlobOperationFailuresTotal, tags)
          durationKey = MetricKey(MetricKeys.BlobOperationDuration, tags)
        yield assertTrue(
          exit.causeOption.flatMap(_.failureOption).contains(failure),
          snapshot.counters.get(operation).contains(1L),
          snapshot.counters.get(failed).contains(1L),
          snapshot.gauges.contains(durationKey),
        )
      },
      test("records get counter") {
        val data = Chunk.fromArray("get-metrics".getBytes(StandardCharsets.UTF_8))
        for
          registry   <- InMemoryMetricsRegistry.make
          blockStore <- InMemoryBlockStore.make
          repo       <- InMemoryBlobManifestRepo.make
          underlying  = new CasBlobStore(blockStore, repo)
          metered     = MetricsBlobStore(underlying, registry, Map("env" -> "test"))

          result   <- ZStream.fromChunk(data).run(metered.put())
          _        <- metered.get(result.key).runCollect
          snapshot <- registry.snapshot

          getKey = MetricKey(MetricKeys.BlobOperationsTotal, Map("env" -> "test", "operation" -> "get"))
        yield assertTrue(
          snapshot.counters.contains(getKey),
          snapshot.counters(getKey) == 1L,
        )
      },
      test("records stat counter") {
        val data = Chunk.fromArray("stat-metrics".getBytes(StandardCharsets.UTF_8))
        for
          registry   <- InMemoryMetricsRegistry.make
          blockStore <- InMemoryBlockStore.make
          repo       <- InMemoryBlobManifestRepo.make
          underlying  = new CasBlobStore(blockStore, repo)
          metered     = MetricsBlobStore(underlying, registry, Map("env" -> "test"))

          result   <- ZStream.fromChunk(data).run(metered.put())
          _        <- metered.stat(result.key)
          snapshot <- registry.snapshot

          statKey = MetricKey(MetricKeys.BlobOperationsTotal, Map("env" -> "test", "operation" -> "stat"))
        yield assertTrue(
          snapshot.counters.contains(statKey)
        )
      },
      test("records delete counter") {
        val data = Chunk.fromArray("delete-metrics".getBytes(StandardCharsets.UTF_8))
        for
          registry   <- InMemoryMetricsRegistry.make
          blockStore <- InMemoryBlockStore.make
          repo       <- InMemoryBlobManifestRepo.make
          underlying  = new CasBlobStore(blockStore, repo)
          metered     = MetricsBlobStore(underlying, registry, Map("env" -> "test"))

          result   <- ZStream.fromChunk(data).run(metered.put())
          _        <- metered.delete(result.key)
          snapshot <- registry.snapshot

          deleteKey = MetricKey(MetricKeys.BlobOperationsTotal, Map("env" -> "test", "operation" -> "delete"))
        yield assertTrue(
          snapshot.counters.contains(deleteKey)
        )
      },
    )
