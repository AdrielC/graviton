package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.runtime.metrics.{InMemoryMetricsRegistry, MetricKey}
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

          putCountKey = MetricKey("graviton.blob.put.count", Map("env" -> "test", "op" -> "put"))
          putBytesKey = MetricKey("graviton.blob.put.bytes", Map("env" -> "test", "op" -> "put"))
        yield assertTrue(
          snapshot.counters.contains(putCountKey),
          snapshot.counters(putCountKey) == 1L,
          snapshot.gauges.contains(putBytesKey),
          snapshot.gauges(putBytesKey) == data.length.toDouble,
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

          getKey = MetricKey("graviton.blob.get.count", Map("env" -> "test", "op" -> "get"))
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

          statKey = MetricKey("graviton.blob.stat.count", Map("env" -> "test", "op" -> "stat"))
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

          deleteKey = MetricKey("graviton.blob.delete.count", Map("env" -> "test", "op" -> "delete"))
        yield assertTrue(
          snapshot.counters.contains(deleteKey)
        )
      },
    )
