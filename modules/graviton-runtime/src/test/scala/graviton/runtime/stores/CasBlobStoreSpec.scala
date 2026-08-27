package graviton.runtime.stores

import graviton.core.attributes.BinaryAttributes
import graviton.core.types.*
import graviton.runtime.metrics.{InMemoryMetricsRegistry, MetricKey, MetricKeys}
import graviton.runtime.model.{BlobWritePlan, BlockWritePlan, CanonicalBlock, IngestProgram, StoredBlock}
import graviton.streams.Chunker
import zio.*
import zio.stream.{ZPipeline, ZStream}
import zio.test.*

import java.nio.charset.StandardCharsets

object CasBlobStoreSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("CasBlobStore")(
      test("uses Chunker boundaries for block spans") {
        val bytes   = "x" * 2500
        val data    = Chunk.fromArray(bytes.getBytes(StandardCharsets.UTF_8))
        val chunker = Chunker.fixed(UploadChunkSize(1024))

        for
          blockStore <- InMemoryBlockStore.make
          repo       <- InMemoryBlobManifestRepo.make
          blobStore   = new CasBlobStore(blockStore, repo)

          result <- Chunker.locally(chunker) {
                      ZStream.fromChunk(data).run(blobStore.put(BlobWritePlan(attributes = BinaryAttributes.empty)))
                    }

          blobKey = result.key
          stored <- repo.get(blobKey).someOrFail(new NoSuchElementException("Manifest missing"))

          spans = stored.manifest.entries.map(_.span)
        yield assertTrue(
          stored.manifest.entries.length == 3,
          spans.head.startInclusive.value == 0L,
          spans.head.endInclusive.value == 1023L,
          spans(1).startInclusive.value == 1024L,
          spans(1).endInclusive.value == 2047L,
          spans(2).startInclusive.value == 2048L,
          spans(2).endInclusive.value == 2499L,
        )
      },
      test("applies BlobWritePlan.program pipeline before chunking + hashing") {
        val input   = "a-b-c-d"
        val data    = Chunk.fromArray(input.getBytes(StandardCharsets.UTF_8))
        val chunker = Chunker.fixed(UploadChunkSize(2))

        val program =
          IngestProgram.UsePipeline(
            zio.stream.ZPipeline.filter[Byte](_ != '-'.toByte)
          )

        for
          blockStore <- InMemoryBlockStore.make
          repo       <- InMemoryBlobManifestRepo.make
          blobStore   = new CasBlobStore(blockStore, repo)

          result <- Chunker.locally(chunker) {
                      ZStream
                        .fromChunk(data)
                        .run(
                          blobStore.put(
                            BlobWritePlan(
                              attributes = BinaryAttributes.empty,
                              program = program,
                            )
                          )
                        )
                    }

          blobKey = result.key
          stored <- repo.get(blobKey).someOrFail(new NoSuchElementException("Manifest missing"))

          bytes <- blobStore.get(blobKey).runCollect
        yield assertTrue(
          bytes == Chunk.fromArray("abcd".getBytes(StandardCharsets.UTF_8)),
          stored.manifest.entries.length == 2,
          stored.manifest.entries.map(_.span.startInclusive) == List(0L, 2L),
          stored.manifest.entries.map(_.span.endInclusive) == List(1L, 3L),
        )
      },
      test("supports IngestProgram.UseScan without breaking ingest (records metrics)") {
        val input = "hello"
        val data  = Chunk.fromArray(input.getBytes(StandardCharsets.UTF_8))

        val program =
          IngestProgram.UseScan(
            label = "byte-count",
            build = () => graviton.core.scan.FS.counter[Byte],
          )

        for
          registry <- InMemoryMetricsRegistry.make

          chunker = Chunker.fixed(UploadChunkSize(2))

          blockStore <- InMemoryBlockStore.make
          repo       <- InMemoryBlobManifestRepo.make
          blobStore   = new CasBlobStore(blockStore, repo, metrics = registry)

          result <- Chunker.locally(chunker) {
                      ZStream
                        .fromChunk(data)
                        .run(blobStore.put(BlobWritePlan(program = program)))
                    }

          blobKey = result.key

          bytes <- blobStore.get(blobKey).runCollect

          snapshot <- registry.snapshot

          tags =
            Map(
              "backend" -> "cas",
              "store"   -> "blob",
              "chunker" -> chunker.name,
              "program" -> "scan",
              "scan"    -> "byte-count",
            )
        yield assertTrue(
          bytes == data,
          snapshot.gauges.contains(MetricKey(MetricKeys.BytesIngested, tags)),
          snapshot.gauges.contains(MetricKey(MetricKeys.BlocksIngested, tags)),
          snapshot.gauges.contains(MetricKey(MetricKeys.ScanOutputs, tags)),
          snapshot.gauges.contains(MetricKey(MetricKeys.UploadDuration, tags)),
          snapshot.counters.get(MetricKey(MetricKeys.BlobIngestsTotal, tags)).contains(1L),
          snapshot.counters.get(MetricKey(MetricKeys.BytesIngestedTotal, tags)).contains(data.length.toLong),
          snapshot.counters.get(MetricKey(MetricKeys.FreshBlocksTotal, tags)).contains(result.stats.freshBlocks.toLong),
          snapshot.counters
            .get(MetricKey(MetricKeys.DuplicateBlocksTotal, tags))
            .contains(result.stats.duplicateBlocks.toLong),
        )
      },
      test("rejects BlobWritePlan attributes with invalid digest metadata") {
        val data  = Chunk.fromArray("abc".getBytes(StandardCharsets.UTF_8))
        val attrs =
          BinaryAttributes.empty
            .advertiseDigest(Algo.applyUnsafe("sha-256"), HexLower.applyUnsafe("a" * 40))

        for
          blockStore <- InMemoryBlockStore.make
          repo       <- InMemoryBlobManifestRepo.make
          blobStore   = new CasBlobStore(blockStore, repo)
          exit       <- ZStream
                          .fromChunk(data)
                          .run(blobStore.put(BlobWritePlan(attributes = attrs)))
                          .exit
        yield assertTrue(
          exit match
            case Exit.Failure(cause) =>
              cause.failureOption.exists(_.getMessage.contains("Invalid binary attributes in BlobWritePlan"))
            case Exit.Success(_)     => false
        )
      },
      test("normalizes a caller-owned large chunk before hashing and chunking") {
        val data    = Chunk.fromArray(Array.tabulate(2 * 1024 * 1024)(index => (index % 251).toByte))
        val ioBytes = UploadChunkSize(8 * 1024)
        val base    = Chunker.fixed(UploadChunkSize(64 * 1024))

        for
          largest    <- Ref.make(0)
          observed    = new Chunker:
                          override val name: String           = "observed-fixed"
                          override val maximumBlockBytes: Int = base.maximumBlockBytes
                          override val pipeline               =
                            ZPipeline.mapChunksZIO[Any, Nothing, Byte, Byte](chunk =>
                              largest.update(current => math.max(current, chunk.length)).as(chunk)
                            ) >>> base.pipeline
          blockStore <- InMemoryBlockStore.make
          repo       <- InMemoryBlobManifestRepo.make
          store       = new CasBlobStore(
                          blockStore,
                          repo,
                          ingestConfig = CasBlobStore.IngestConfig(
                            ioChunkBytes = ioBytes,
                            inputBufferChunks = 2,
                            blockBufferBlocks = 1,
                          ),
                        )
          result     <- Chunker.locally(observed)(ZStream.fromChunk(data).run(store.put()))
          restored   <- store.get(result.key).runCollect
          maximum    <- largest.get
        yield assertTrue(
          restored == data,
          maximum <= ioBytes.value,
          result.stats.totalBytes == data.length.toLong,
        )
      },
      test("fails when a chunker violates its declared block ceiling") {
        val base      = Chunker.fixed(UploadChunkSize(1024))
        val dishonest = new Chunker:
          override val name: String           = "dishonest"
          override val maximumBlockBytes: Int = 512
          override val pipeline               = base.pipeline

        for
          blockStore <- InMemoryBlockStore.make
          repo       <- InMemoryBlobManifestRepo.make
          store       = new CasBlobStore(blockStore, repo)
          exit       <- Chunker
                          .locally(dishonest)(ZStream.fromChunk(Chunk.fill(1024)(1.toByte)).run(store.put()))
                          .exit
          manifests  <- repo.list
        yield assertTrue(
          exit match
            case Exit.Failure(cause) =>
              cause.failureOption.exists(_.getMessage.contains("above its declared 512-byte maximum"))
            case Exit.Success(_)     => false,
          manifests.isEmpty,
        )
      },
      test("backpressures the source when block persistence stops") {
        val chunks = 100

        for
          pulls        <- Ref.make(0)
          writeStarted <- Promise.make[Nothing, Unit]
          release      <- Promise.make[Nothing, Unit]
          delegate     <- InMemoryBlockStore.make
          slowStore     = new BlockStore:
                            override def putBlocks(plan: BlockWritePlan)                                          = delegate.putBlocks(plan)
                            override def putBlock(block: CanonicalBlock, plan: BlockWritePlan): Task[StoredBlock] =
                              writeStarted.succeed(()).ignore *> release.await *> delegate.putBlock(block, plan)
                            override def get(key: graviton.core.keys.BinaryKey.Block)                             = delegate.get(key)
                            override def exists(key: graviton.core.keys.BinaryKey.Block)                          = delegate.exists(key)
          repo         <- InMemoryBlobManifestRepo.make
          store         = new CasBlobStore(
                            slowStore,
                            repo,
                            ingestConfig = CasBlobStore.IngestConfig(
                              ioChunkBytes = UploadChunkSize(1024),
                              inputBufferChunks = 2,
                              blockBufferBlocks = 1,
                            ),
                          )
          source        = ZStream.unfoldChunkZIO(0) { index =>
                            if index >= chunks then ZIO.none
                            else pulls.update(_ + 1).as(Some(Chunk.fill(1024)(index.toByte) -> (index + 1)))
                          }
          fiber        <- Chunker
                            .locally(Chunker.fixed(UploadChunkSize(1024)))(source.run(store.put()))
                            .fork
          _            <- writeStarted.await
          _            <- ZIO.yieldNow.repeatN(32)
          observed     <- pulls.get
          _            <- release.succeed(())
          result       <- fiber.join
        yield assertTrue(
          observed < chunks,
          observed <= 8,
          result.stats.totalBytes == chunks.toLong * 1024L,
        )
      },
      test("propagates a failed block write without deadlocking the upload") {
        val failure = new java.io.IOException("block backend unavailable")

        for
          delegate  <- InMemoryBlockStore.make
          broken     = new BlockStore:
                         override def putBlocks(plan: BlockWritePlan)                                          = delegate.putBlocks(plan)
                         override def putBlock(block: CanonicalBlock, plan: BlockWritePlan): Task[StoredBlock] =
                           ZIO.fail(failure)
                         override def get(key: graviton.core.keys.BinaryKey.Block)                             = delegate.get(key)
                         override def exists(key: graviton.core.keys.BinaryKey.Block)                          = delegate.exists(key)
          repo      <- InMemoryBlobManifestRepo.make
          store      = new CasBlobStore(broken, repo)
          completed <- Live.live(
                         ZStream
                           .fromChunk(Chunk.fill(4 * 1024 * 1024)(1.toByte))
                           .run(store.put())
                           .exit
                           .timeout(5.seconds)
                       )
        yield assertTrue(
          completed.exists(_.isFailure),
          completed.flatMap(_.causeOption.flatMap(_.failureOption)).contains(failure),
        )
      },
      test("interrupting an upload releases its source without publishing a manifest") {
        for
          released    <- Ref.make(false)
          firstPull   <- Promise.make[Nothing, Unit]
          blockStore  <- InMemoryBlockStore.make
          repo        <- InMemoryBlobManifestRepo.make
          store        = new CasBlobStore(blockStore, repo)
          source       = ZStream
                           .acquireReleaseWith(ZIO.unit)(_ => released.set(true))
                           .flatMap(_ => ZStream.repeatZIO(firstPull.succeed(()).ignore.as(1.toByte)))
          fiber       <- Chunker
                           .locally(Chunker.fixed(UploadChunkSize(1024)))(source.run(store.put()))
                           .fork
          _           <- firstPull.await
          _           <- fiber.interrupt
          wasReleased <- released.get
          manifests   <- repo.list
        yield assertTrue(
          wasReleased,
          manifests.isEmpty,
        )
      },
      test("the bounded manifest and counters represent a logical 1 TiB blob") {
        val oneMiB  = 1024L * 1024L
        val oneTiB  = 1024L * 1024L * 1024L * 1024L
        val chunker = Chunker.fixed(UploadChunkSize(1024 * 1024))
        val config  = CasBlobStore.IngestConfig()

        assertTrue(
          BlobManifestRepo.MaxEntries.toLong * oneMiB == oneTiB,
          FileSize.either(oneTiB).isRight,
          config.maximumQueuedBytes(chunker) ==
            config.inputBufferChunks.toLong * config.ioChunkBytes.value.toLong +
            config.blockBufferBlocks.toLong * chunker.maximumBlockBytes.toLong,
          config.maximumQueuedBytes(chunker) == 2_359_296L,
        )
      },
    )
