package graviton.runtime.stores

import graviton.core.attributes.BinaryAttributes
import graviton.core.types.*
import graviton.runtime.config.{BlockPersistenceConfig, TransferMemoryConfig, TransferMemoryLimit}
import graviton.runtime.metrics.{InMemoryMetricsRegistry, MetricKey, MetricKeys}
import graviton.runtime.model.{BlobWritePlan, BlockWritePlan, CanonicalBlock, IngestProgram, StoredBlock}
import graviton.runtime.streaming.BlobStreamer
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
            .getOrElse(0L) == result.stats.duplicateBlocks.toLong,
          snapshot.counters.get(MetricKey(MetricKeys.FreshBlockBytesTotal, tags)).contains(data.length.toLong),
          snapshot.counters.get(MetricKey(MetricKeys.DuplicateBlockBytesTotal, tags)).getOrElse(0L) == 0L,
        )
      },
      test("accounts for both bounded scan broadcast queues in the transfer footprint") {
        val chunker = Chunker.fixed(UploadChunkSize(1024 * 1024))
        val program = IngestProgram.UseScan(
          label = "byte-count",
          build = () => graviton.core.scan.FS.counter[Byte],
        )

        for
          blockStore <- InMemoryBlockStore.make
          baseline   <- ZIO.fromEither(
                          CasBlobStore
                            .IngestConfig()
                            .maximumPipelineFootprint(
                              chunker,
                              BlockPersistenceConfig.default,
                              blockStore,
                            )
                        )
          withScan   <- ZIO.fromEither(
                          CasBlobStore
                            .IngestConfig()
                            .maximumPipelineFootprint(
                              chunker,
                              BlockPersistenceConfig.default,
                              blockStore,
                              program,
                              scanWindowRefs = 8,
                            )
                        )
        yield assertTrue(
          withScan.totalBytes - baseline.totalBytes == 2L * 8L * 64L * 1024L,
          withScan.contributions.exists(_.component.value == "scan-broadcast-queues"),
        )
      },
      test("records byte-weighted CAS reuse independently of block counts") {
        val data    = Chunk.fromArray("seven-byte blocks with a short tail".getBytes(StandardCharsets.UTF_8))
        val chunker = Chunker.fixed(UploadChunkSize(7))

        for
          registry   <- InMemoryMetricsRegistry.make
          blockStore <- InMemoryBlockStore.make
          repo       <- InMemoryBlobManifestRepo.make
          blobStore   = new CasBlobStore(blockStore, repo, metrics = registry)
          first      <- Chunker.locally(chunker)(ZStream.fromChunk(data).run(blobStore.put()))
          second     <- Chunker.locally(chunker)(ZStream.fromChunk(data).run(blobStore.put()))
          snapshot   <- registry.snapshot
          tags        = Map(
                          "backend" -> "cas",
                          "store"   -> "blob",
                          "chunker" -> chunker.name,
                          "program" -> "default",
                        )
        yield assertTrue(
          first.stats.freshBlocks == first.stats.blockCount,
          second.stats.duplicateBlocks == second.stats.blockCount,
          snapshot.counters.get(MetricKey(MetricKeys.FreshBlockBytesTotal, tags)).contains(data.length.toLong),
          snapshot.counters.get(MetricKey(MetricKeys.DuplicateBlockBytesTotal, tags)).contains(data.length.toLong),
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
      test("range reads fetch only intersecting CAS blocks") {
        val blockBytes = 1024
        val data       = Chunk.fromArray(Array.tabulate(4 * blockBytes)(index => (index % 251).toByte))
        val start      = 3L * blockBytes + 100L
        val length     = 32L

        for
          delegate  <- InMemoryBlockStore.make
          repo      <- InMemoryBlobManifestRepo.make
          writer     = new CasBlobStore(delegate, repo)
          result    <- Chunker.locally(Chunker.fixed(UploadChunkSize(1024)))(ZStream.fromChunk(data).run(writer.put()))
          fetched   <- Ref.make(Chunk.empty[graviton.core.keys.BinaryKey.Block])
          tracking   = new BlockStore:
                         override def putBlocks(plan: BlockWritePlan)                 = delegate.putBlocks(plan)
                         override def exists(key: graviton.core.keys.BinaryKey.Block) = delegate.exists(key)
                         override def get(key: graviton.core.keys.BinaryKey.Block)    =
                           ZStream.fromZIO(fetched.update(_ :+ key)).drain ++ delegate.get(key)
          reader     = new CasBlobStore(tracking, repo)
          bytes     <- reader
                         .getRange(result.key, BlobOffset.unsafe(start), FileSize.unsafe(length))
                         .runCollect
          requested <- fetched.get
          stored    <- repo.get(result.key).someOrFail(new NoSuchElementException("Manifest missing"))
          lastKey    = stored.manifest.entries.last.key
        yield assertTrue(
          bytes == data.slice(start.toInt, (start + length).toInt),
          requested.length == 1,
          requested.head == lastKey,
        )
      },
      test("interrupted downloads release their complete prefetch reservation") {
        val capacity = 64L * 1024L * 1024L
        val data     = Chunk.fill(1024)(1.toByte)

        for
          delegate <- InMemoryBlockStore.make
          repo     <- InMemoryBlobManifestRepo.make
          writer    = new CasBlobStore(delegate, repo)
          result   <- Chunker.locally(Chunker.fixed(UploadChunkSize(1024)))(ZStream.fromChunk(data).run(writer.put()))
          entered  <- Promise.make[Nothing, Unit]
          gate     <- Promise.make[Nothing, Unit]
          blocking  = new BlockStore:
                        override def putBlocks(plan: BlockWritePlan)                 = delegate.putBlocks(plan)
                        override def exists(key: graviton.core.keys.BinaryKey.Block) = delegate.exists(key)
                        override def get(key: graviton.core.keys.BinaryKey.Block)    =
                          ZStream.fromZIO(entered.succeed(()) *> gate.await).drain ++ delegate.get(key)
          budget   <- TransferBudget.make(TransferMemoryConfig(TransferMemoryLimit.applyUnsafe(capacity)))
          reader    = new CasBlobStore(blocking, repo, transferBudget = budget)
          fiber    <- reader.get(result.key).runDrain.fork
          _        <- entered.await
          during   <- budget.availableBytes
          _        <- fiber.interrupt
          after    <- budget.availableBytes
        yield assertTrue(
          during == capacity - BlobStreamer.Config().maximumPrefetchedBytes,
          after == capacity,
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
          manifests  <- repo.keys
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
                            override def putBlocks(plan: BlockWritePlan)                                                    = delegate.putBlocks(plan)
                            override def putBlock(block: CanonicalBlock, plan: BlockWritePlan): IO[StoreError, StoredBlock] =
                              writeStarted.succeed(()).ignore *> release.await *> delegate.putBlock(block, plan)
                            override def get(key: graviton.core.keys.BinaryKey.Block)                                       = delegate.get(key)
                            override def exists(key: graviton.core.keys.BinaryKey.Block)                                    = delegate.exists(key)
          repo         <- InMemoryBlobManifestRepo.make
          store         = new CasBlobStore(
                            slowStore,
                            repo,
                            ingestConfig = CasBlobStore.IngestConfig(
                              ioChunkBytes = UploadChunkSize(1024),
                              inputBufferChunks = 2,
                              blockBufferBlocks = 1,
                            ),
                            persistenceConfig = BlockPersistenceConfig.sequential,
                          )
          source        = ZStream.unfoldChunkZIO(0) { index =>
                            if index >= chunks then ZIO.none
                            else pulls.update(_ + 1).as(Some(Chunk.fill(1024)(index.toByte) -> (index + 1)))
                          }
          fiber        <- Chunker
                            .locally(Chunker.fixed(UploadChunkSize(1024)))(source.run(store.put()))
                            .fork
          _            <- writeStarted.await
          _            <- TestClock.adjust(1.millis)
          observed     <- pulls.get
          _            <- release.succeed(())
          result       <- fiber.join
        yield assertTrue(
          observed < chunks,
          observed <= 8,
          result.stats.totalBytes == chunks.toLong * 1024L,
        )
      },
      test("persists bounded blocks concurrently without reordering the manifest") {
        val parallelism = BlockWriteParallelism.applyUnsafe(4)
        val blockSize   = UploadChunkSize(1024)
        val data        = Chunk.fromArray(Array.tabulate(8 * blockSize.value)(index => (index % 251).toByte))

        for
          active     <- Ref.make(0)
          peak       <- Ref.make(0)
          started    <- Ref.make(0)
          allStarted <- Promise.make[Nothing, Unit]
          release    <- Promise.make[Nothing, Unit]
          delegate   <- InMemoryBlockStore.make
          concurrent  = new BlockStore:
                          override def putBlocks(plan: BlockWritePlan)                                                    = delegate.putBlocks(plan)
                          override def putBlock(block: CanonicalBlock, plan: BlockWritePlan): IO[StoreError, StoredBlock] =
                            (for
                              now   <- active.updateAndGet(_ + 1)
                              _     <- peak.update(current => math.max(current, now))
                              count <- started.updateAndGet(_ + 1)
                              _     <- ZIO.when(count >= parallelism.value)(allStarted.succeed(()).ignore)
                              _     <- release.await
                              saved <- delegate.putBlock(block, plan)
                            yield saved).ensuring(active.update(_ - 1))
                          override def get(key: graviton.core.keys.BinaryKey.Block)                                       = delegate.get(key)
                          override def exists(key: graviton.core.keys.BinaryKey.Block)                                    = delegate.exists(key)
          repo       <- InMemoryBlobManifestRepo.make
          store       = new CasBlobStore(
                          concurrent,
                          repo,
                          persistenceConfig = BlockPersistenceConfig(parallelism),
                        )
          fiber      <- Chunker.locally(Chunker.fixed(blockSize))(ZStream.fromChunk(data).run(store.put())).fork
          _          <- Live.live(
                          allStarted.await.timeoutFail(new IllegalStateException("parallel block writes did not start"))(5.seconds)
                        )
          observed   <- peak.get
          _          <- release.succeed(())
          result     <- fiber.join
          restored   <- store.get(result.key).runCollect
        yield assertTrue(
          observed == parallelism.value,
          restored == data,
          result.stats.blockCount == 8,
        )
      },
      test("propagates a failed block write without deadlocking the upload") {
        val cause   = new java.io.IOException("block backend unavailable")
        val failure = StoreError.Unavailable(StoreOperation.PutBlock, StoreBackend.InMemory, cause)

        for
          delegate  <- InMemoryBlockStore.make
          broken     = new BlockStore:
                         override def putBlocks(plan: BlockWritePlan)                                                    = delegate.putBlocks(plan)
                         override def putBlock(block: CanonicalBlock, plan: BlockWritePlan): IO[StoreError, StoredBlock] =
                           ZIO.fail(failure)
                         override def get(key: graviton.core.keys.BinaryKey.Block)                                       = delegate.get(key)
                         override def exists(key: graviton.core.keys.BinaryKey.Block)                                    = delegate.exists(key)
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
          manifests   <- repo.keys
        yield assertTrue(
          wasReleased,
          manifests.isEmpty,
        )
      },
      test("the bounded manifest and counters represent a logical 1 TiB blob") {
        val oneMiB      = 1024L * 1024L
        val oneTiB      = 1024L * 1024L * 1024L * 1024L
        val chunker     = Chunker.fixed(UploadChunkSize(1024 * 1024))
        val config      = CasBlobStore.IngestConfig()
        val persistence = BlockPersistenceConfig.default

        assertTrue(
          BlobManifestRepo.MaxEntries.toLong * oneMiB == oneTiB,
          FileSize.either(oneTiB).isRight,
          config.maximumQueuedBytes(chunker) ==
            config.inputBufferChunks.toLong * config.ioChunkBytes.value.toLong +
            config.blockBufferBlocks.toLong * chunker.maximumBlockBytes.toLong,
          config.maximumQueuedBytes(chunker) == 2_359_296L,
          config.maximumPipelineBytes(chunker, persistence) == 11_796_480L,
        )
      },
      test("block persistence configuration rejects unbounded concurrency") {
        val tooSmall = ConfigProvider.fromMap(Map("graviton.block-write-parallelism" -> "0"))
        val tooLarge = ConfigProvider.fromMap(Map("graviton.block-write-parallelism" -> "65"))
        val default  = ConfigProvider.fromMap(Map.empty)

        for
          smallExit  <- ZIO.withConfigProvider(tooSmall)(ZIO.config(BlockPersistenceConfig.config)).exit
          largeExit  <- ZIO.withConfigProvider(tooLarge)(ZIO.config(BlockPersistenceConfig.config)).exit
          configured <- ZIO.withConfigProvider(default)(ZIO.config(BlockPersistenceConfig.config))
        yield assertTrue(
          smallExit.isFailure,
          largeExit.isFailure,
          configured == BlockPersistenceConfig.default,
        )
      },
    )
