package graviton.runtime.stores

import graviton.core.attributes.BinaryAttributes
import graviton.core.bytes.{Digest, HashAlgo, Hasher}
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.types.FileSize
import graviton.runtime.config.GarbageCollectionConfig
import graviton.runtime.model.{BlockWritePlan, CanonicalBlock, InventoryCursor, InventoryPage, InventoryPageSize, StoredBlock}
import graviton.runtime.streaming.BlobStreamer
import zio.*
import zio.stream.ZStream
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.nio.file.attribute.FileTime
import java.time.Instant

object GarbageCollectorSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] = suite("GarbageCollector")(
    test("dry-run, quarantine, and restore preserve referenced blocks") {
      withTempDir { root =>
        for
          blocks      <- ZIO.succeed(new FsBlockStore(root))
          manifests   <- ZIO.succeed(new FsBlobManifestRepo(root))
          cas         <- ZIO.succeed(new CasBlobStore(blocks, manifests))
          written     <- ZStream.fromIterable("referenced-data".getBytes(StandardCharsets.UTF_8)).run(cas.put())
          referenced  <- cas.inspect(written.key).someOrFail(new IllegalStateException("missing manifest"))
          orphan      <- canonical("orphan-data")
          _           <- ZStream.succeed(orphan).run(blocks.putBlocks())
          now          = Instant.parse("2030-01-01T00:00:00Z")
          old          = FileTime.from(now.minusSeconds(3600))
          _           <- ZIO.attemptBlocking(Files.setLastModifiedTime(blocks.pathFor(orphan.key), old))
          _           <- TestClock.setTime(now)
          collector    = new GarbageCollector(manifests, blocks)
          dry         <- collector.collect(1.minute, dryRun = true)
          stillThere  <- blocks.exists(orphan.key)
          swept       <- collector.collect(1.minute, dryRun = false)
          removed     <- blocks.exists(orphan.key)
          _           <- collector.restore(swept.quarantined)
          restored    <- blocks.exists(orphan.key)
          liveBlocks   = referenced.blocks.map(_.key).toSet
          livePresent <- ZIO.foreach(liveBlocks)(blocks.exists)
        yield assertTrue(
          dry.candidateBlocks == 1,
          stillThere,
          swept.quarantined.length == 1,
          !removed,
          restored,
          livePresent.forall(identity),
        )
      }
    },
    test("sweep uses streamed manifests and spill partitions instead of list") {
      val blockCount = 4096
      val blob       = blobKey(blockCount)
      val summary    = StoredManifestSummary(FileSize.unsafe(blockCount.toLong), blockCount, Instant.EPOCH)

      for
        quarantines <- Ref.make(0)
        _           <- TestClock.setTime(Instant.EPOCH.plusSeconds(1L))
        maintenance  = new CountingMaintenance(
                         ZStream.range(0, blockCount).map(index => BlockInventoryEntry(blockKey(index), 1L, Instant.EPOCH)),
                         quarantines,
                       )
        manifests    = new StreamingOnlyManifestRepo(
                         ZStream.succeed(blob -> summary),
                         _ => ZStream.range(0, blockCount).map(index => BlobStreamer.BlockRef(index.toLong, blockKey(index))),
                       )
        collector    = new GarbageCollector(
                         manifests,
                         maintenance,
                         GarbageCollectionConfig(maxReferencesPerPartition = 4, maximumPartitionDepth = 64),
                       )
        report      <- collector.sweep(Duration.Zero, dryRun = true)()
        observed    <- quarantines.get
      yield assertTrue(
        report.scannedBlocks == blockCount.toLong,
        report.referencedBlockRefs == blockCount.toLong,
        report.candidateBlocks == 0L,
        observed == 0,
      )
    },
    test("sub-millisecond negative age fails before touching the inventory") {
      for
        inventoryTouches <- Ref.make(0)
        quarantines      <- Ref.make(0)
        maintenance       = new CountingMaintenance(
                              ZStream.fromZIO(inventoryTouches.update(_ + 1)).drain,
                              quarantines,
                            )
        manifests         = new StreamingOnlyManifestRepo(ZStream.empty, _ => ZStream.empty)
        exit             <- new GarbageCollector(manifests, maintenance).sweep(Duration.fromNanos(-1L), dryRun = true)().exit
        observed         <- inventoryTouches.get
      yield assertTrue(exit.isFailure, observed == 0)
    },
    test("second mark protects a candidate that becomes referenced before quarantine") {
      val block   = blockKey(9)
      val blob    = blobKey(1)
      val summary = StoredManifestSummary(FileSize.unsafe(1L), 1, Instant.EPOCH)

      for
        markPasses  <- Ref.make(0)
        quarantines <- Ref.make(0)
        manifests    = new SwitchingManifestRepo(markPasses, blob, summary, block)
        maintenance  = new CountingMaintenance(
                         ZStream.succeed(BlockInventoryEntry(block, 1L, Instant.EPOCH)),
                         quarantines,
                       )
        collector    = new GarbageCollector(manifests, maintenance)
        report      <- collector.sweep(Duration.Zero, dryRun = false)()
        passes      <- markPasses.get
        observed    <- quarantines.get
      yield assertTrue(
        report.candidateBlocks == 1L,
        report.quarantinedBlocks == 0L,
        passes == 2,
        observed == 0,
      )
    },
    test("exclusive maintenance cannot observe blocks before their manifest commits") {
      withTempDir { root =>
        for
          coordinator      <- FileMaintenanceCoordinator.make(root)
          persisted        <- Promise.make[Nothing, Unit]
          releaseCommit    <- Promise.make[Nothing, Unit]
          sweepStarted     <- Promise.make[Nothing, Unit]
          inventoryTouched <- Promise.make[Nothing, Unit]
          underlying        = new FsBlockStore(root)
          blocks            = new CommitBlockingBlockStore(underlying, persisted, releaseCommit)
          maintenance       = new InventorySignallingMaintenance(underlying, inventoryTouched)
          manifests         = new FsBlobManifestRepo(root)
          rawStore          = new CasBlobStore(blocks, manifests)
          store             = new CoordinatedBlobStore(rawStore, coordinator)
          collector         = new GarbageCollector(
                                manifests,
                                maintenance,
                                GarbageCollectionConfig.Default,
                                coordinator,
                              )
          upload           <- ZStream
                                .fromIterable("manifest-commit-atomicity".getBytes(StandardCharsets.UTF_8))
                                .run(store.put())
                                .fork
          _                <- persisted.await
          sweep            <- (sweepStarted.succeed(()) *> collector.sweep(Duration.Zero, dryRun = false)()).fork
          _                <- sweepStarted.await
          _                <- TestClock.adjust(1.millis)
          observedTooSoon  <- inventoryTouched.isDone
          _                <- releaseCommit.succeed(())
          written          <- upload.join
          report           <- sweep.join
          present          <- store.stat(written.key)
          _                <- TestClock.adjust(1.millis)
        yield assertTrue(
          !observedTooSoon,
          report.candidateBlocks == 0L,
          report.quarantinedBlocks == 0L,
          present.nonEmpty,
        )
      }
    },
    test("restores a block when writing the streaming quarantine receipt fails") {
      withTempDir { root =>
        for
          blocks    <- ZIO.succeed(new FsBlockStore(root))
          manifests <- ZIO.succeed(new FsBlobManifestRepo(root))
          orphan    <- canonical("receipt-compensation")
          _         <- ZStream.succeed(orphan).run(blocks.putBlocks())
          now        = Instant.parse("2030-01-01T00:00:00Z")
          _         <- ZIO.attemptBlocking(Files.setLastModifiedTime(blocks.pathFor(orphan.key), FileTime.from(now.minusSeconds(60))))
          _         <- TestClock.setTime(now)
          collector  = new GarbageCollector(manifests, blocks)
          exit      <- collector
                         .sweep(1.minute, dryRun = false)(_ =>
                           ZIO.fail(
                             StoreError.Unavailable(
                               StoreOperation.Quarantine,
                               StoreBackend.InMemory,
                               new RuntimeException("receipt sink unavailable"),
                             )
                           )
                         )
                         .exit
          present   <- blocks.exists(orphan.key)
        yield assertTrue(exit.isFailure, present)
      }
    },
    test("interrupting a sweep closes and removes its temporary workspace") {
      withTempDir { root =>
        for
          inventoryStarted <- Promise.make[Nothing, Unit]
          quarantines      <- Ref.make(0)
          workspace         = root.resolve("gc-workspace")
          maintenance       = new CountingMaintenance(
                                ZStream.fromZIO(inventoryStarted.succeed(())).drain ++ ZStream.never,
                                quarantines,
                              )
          manifests         = new StreamingOnlyManifestRepo(ZStream.empty, _ => ZStream.empty)
          collector         = new GarbageCollector(
                                manifests,
                                maintenance,
                                GarbageCollectionConfig(workspaceDirectory = Some(workspace)),
                              )
          fiber            <- collector.sweep(Duration.Zero, dryRun = true)().fork
          _                <- inventoryStarted.await
          _                <- fiber.interrupt
          children         <- ZIO.attemptBlocking {
                                if !Files.exists(workspace) then 0L
                                else
                                  val paths = Files.list(workspace)
                                  try paths.count()
                                  finally paths.close()
                              }
        yield assertTrue(children == 0L)
      }
    },
    test("service layer is orthogonal to storage implementations and configuration source") {
      val block = blockKey(42)

      for
        quarantines <- Ref.make(0)
        maintenance  = new CountingMaintenance(
                         ZStream.succeed(BlockInventoryEntry(block, 1L, Instant.EPOCH)),
                         quarantines,
                       )
        manifests    = new StreamingOnlyManifestRepo(ZStream.empty, _ => ZStream.empty)
        coordinator <- MaintenanceCoordinator.inProcess()
        report      <- GarbageCollection
                         .sweep(Duration.Zero, dryRun = true)
                         .provide(
                           (ZLayer.succeed[BlobManifestRepo](manifests) ++
                             ZLayer.succeed[BlockMaintenance](maintenance) ++
                             ZLayer.succeed[MaintenanceCoordinator](coordinator) ++
                             ZLayer.succeed[GarbageCollectionConfig](GarbageCollectionConfig(maxReferencesPerPartition = 4))) >>>
                             GarbageCollection.live
                         )
        observed    <- quarantines.get
        _           <- TestClock.adjust(1.millis)
      yield assertTrue(report.candidateBlocks == 1L, report.quarantinedBlocks == 0L, observed == 0)
    } @@ TestAspect.withLiveClock,
    test("ZIO Config rejects invalid GC memory bounds before a service is built") {
      val provider = ConfigProvider.fromMap(
        Map("graviton.gc.max-references-per-partition" -> "0")
      )

      for exit <- ZIO.withConfigProvider(provider)(ZIO.config(GarbageCollectionConfig.config)).exit
      yield assertTrue(exit.isFailure)
    },
    test("explicit service wiring rejects invalid configuration before any sweep runs") {
      for
        quarantines <- Ref.make(0)
        manifests    = new StreamingOnlyManifestRepo(ZStream.empty, _ => ZStream.empty)
        maintenance  = new CountingMaintenance(ZStream.empty, quarantines)
        exit        <- GarbageCollection.service
                         .provide(
                           (ZLayer.succeed[BlobManifestRepo](manifests) ++ ZLayer.succeed[BlockMaintenance](maintenance)) >>>
                             GarbageCollection.configured(GarbageCollectionConfig(maxReferencesPerPartition = 0))
                         )
                         .exit
      yield assertTrue(exit.isFailure)
    },
  )

  private class StreamingOnlyManifestRepo(
    summaries: ZStream[Any, StoreError, (BinaryKey.Blob, StoredManifestSummary)],
    refs: BinaryKey.Blob => ZStream[Any, StoreError, BlobStreamer.BlockRef],
  ) extends BlobManifestRepo:
    override def put(
      blob: BinaryKey.Blob,
      manifest: graviton.core.manifest.Manifest,
      ingestedAt: Instant,
    ): IO[StoreError, Unit] =
      ZIO.fail(StoreError.Conflict(StoreOperation.PutManifest, "test repository is read-only"))

    override def get(blob: BinaryKey.Blob): IO[StoreError, Option[StoredManifest]] = ZIO.none

    override def inventoryPage(
      after: Option[InventoryCursor],
      limit: InventoryPageSize,
    ): IO[StoreError, InventoryPage[(BinaryKey.Blob, StoredManifestSummary)]] =
      after match
        case Some(_) => ZIO.fail(StoreError.InvalidInput(StoreOperation.Inventory, "test cursor is unsupported"))
        case None    => summaries.take(limit.value.toLong).runCollect.map(InventoryPage(_, None))

    override def streamBlockRefs(blob: BinaryKey.Blob): ZStream[Any, StoreError, BlobStreamer.BlockRef] = refs(blob)

    override def delete(blob: BinaryKey.Blob): IO[StoreError, Boolean] = ZIO.succeed(false)

    override def healthCheck: IO[StoreError, Unit] = ZIO.unit

  private final class SwitchingManifestRepo(
    markPasses: Ref[Int],
    blob: BinaryKey.Blob,
    summary: StoredManifestSummary,
    referenced: BinaryKey.Block,
  ) extends StreamingOnlyManifestRepo(
        ZStream.empty,
        _ => ZStream.succeed(BlobStreamer.BlockRef(0L, referenced)),
      ):
    override def inventoryPage(
      after: Option[InventoryCursor],
      limit: InventoryPageSize,
    ): IO[StoreError, InventoryPage[(BinaryKey.Blob, StoredManifestSummary)]] =
      markPasses.updateAndGet(_ + 1).map { pass =>
        val items = if pass == 1 then Chunk.empty else Chunk(blob -> summary)
        InventoryPage(items, None)
      }

  private final class CountingMaintenance(
    override val inventory: ZStream[Any, StoreError, BlockInventoryEntry],
    quarantines: Ref[Int],
  ) extends BlockMaintenance:
    override def quarantine(entry: BlockInventoryEntry): IO[StoreError, QuarantinedBlock] =
      Clock.instant.flatMap { now =>
        quarantines.updateAndGet(_ + 1).map { count =>
          QuarantinedBlock(entry.key, s"test-$count", entry.size, now)
        }
      }

    override def restore(block: QuarantinedBlock): IO[StoreError, Unit] = ZIO.unit

    override def purge(block: QuarantinedBlock): IO[StoreError, Unit] = ZIO.unit

  private final class CommitBlockingBlockStore(
    delegate: BlockStore,
    persisted: Promise[Nothing, Unit],
    releaseCommit: Promise[Nothing, Unit],
  ) extends BlockStore:
    override def putBlock(block: CanonicalBlock, plan: BlockWritePlan): IO[StoreError, StoredBlock] =
      delegate.putBlock(block, plan).flatMap(stored => persisted.succeed(()) *> releaseCommit.await.as(stored))

    override def putBlocks(plan: BlockWritePlan): BlockSink                = delegate.putBlocks(plan)
    override def get(key: BinaryKey.Block): ZStream[Any, StoreError, Byte] = delegate.get(key)
    override def exists(key: BinaryKey.Block): IO[StoreError, Boolean]     = delegate.exists(key)
    override def healthCheck: IO[StoreError, Unit]                         = delegate.healthCheck

  private final class InventorySignallingMaintenance(
    delegate: BlockMaintenance,
    inventoryTouched: Promise[Nothing, Unit],
  ) extends BlockMaintenance:
    override val inventory: ZStream[Any, StoreError, BlockInventoryEntry]                 =
      ZStream.fromZIO(inventoryTouched.succeed(())).drain ++ delegate.inventory
    override def quarantine(entry: BlockInventoryEntry): IO[StoreError, QuarantinedBlock] = delegate.quarantine(entry)
    override def restore(block: QuarantinedBlock): IO[StoreError, Unit]                   = delegate.restore(block)
    override def purge(block: QuarantinedBlock): IO[StoreError, Unit]                     = delegate.purge(block)

  private def canonical(value: String): Task[CanonicalBlock] =
    val bytes = Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8))
    for
      hasher <- ZIO.fromEither(Hasher.systemDefault).mapError(new IllegalArgumentException(_))
      _      <- ZIO.attempt(hasher.update(bytes.toArray))
      digest <- ZIO.fromEither(hasher.digest).mapError(new IllegalArgumentException(_))
      bits   <- ZIO.fromEither(KeyBits.create(hasher.algo, digest, bytes.length.toLong)).mapError(new IllegalArgumentException(_))
      key    <- ZIO.fromEither(BinaryKey.block(bits)).mapError(new IllegalArgumentException(_))
      block  <- ZIO.fromEither(CanonicalBlock.make(key, bytes, BinaryAttributes.empty)).mapError(new IllegalArgumentException(_))
    yield block

  private def blockKey(index: Int): BinaryKey.Block =
    val digest = Digest.fromString(f"$index%064x").fold(message => throw new IllegalArgumentException(message), identity)
    val bits   = KeyBits
      .create(HashAlgo.Sha256, digest, 1L)
      .fold(message => throw new IllegalArgumentException(message), identity)
    BinaryKey.block(bits).fold(message => throw new IllegalArgumentException(message), identity)

  private def blobKey(index: Int): BinaryKey.Blob =
    val digest = Digest.fromString(f"${index + 1000000}%064x").fold(message => throw new IllegalArgumentException(message), identity)
    val bits   = KeyBits
      .create(HashAlgo.Sha256, digest, math.max(1, index).toLong)
      .fold(message => throw new IllegalArgumentException(message), identity)
    BinaryKey.blob(bits).fold(message => throw new IllegalArgumentException(message), identity)

  private def withTempDir[A](effect: Path => ZIO[Any, Throwable, A]): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(ZIO.attemptBlocking(Files.createTempDirectory("graviton-gc-")))(path =>
      ZIO.attemptBlocking {
        val paths = Files.walk(path)
        try
          paths.sorted(java.util.Comparator.reverseOrder()).forEach { item =>
            val _ = Files.deleteIfExists(item); ()
          }
        finally paths.close()
      }.orDie
    )(effect)
