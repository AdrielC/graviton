package graviton.runtime.tenant

import graviton.core.types.UploadChunkSize
import graviton.runtime.config.{BlockPersistenceConfig, TenantStorageConfig, TransferMemoryConfig}
import graviton.runtime.metrics.{InMemoryMetricsRegistry, MetricKeys}
import graviton.runtime.stores.*
import graviton.runtime.upload.TenantId
import graviton.streams.{BoundedByteStream, Chunker}
import zio.*
import zio.stream.ZStream
import zio.test.*

object TenantStorageSpec extends ZIOSpecDefault:
  private val TenantA = TenantId.applyUnsafe("00000000-0000-4000-8000-000000000a01")
  private val TenantB = TenantId.applyUnsafe("00000000-0000-4000-8000-000000000b01")
  private val Unknown = TenantId.applyUnsafe("00000000-0000-4000-8000-000000000c01")
  private val Shared  = DeduplicationDomainId.applyUnsafe("shared-customer-corpus")
  private val Chunk64 = UploadChunkSize.applyUnsafe(64 * 1024)
  private val Payload = Chunk.fromArray(Array.tabulate[Byte](256 * 1024)(index => (index % 251).toByte))

  override def spec =
    suite("tenant storage")(
      test("missing context fails before pulling upload bytes") {
        withTopology(shared = false) { fixture =>
          for
            pulls <- Ref.make(0)
            source = ZStream.fromZIO(pulls.updateAndGet(_ + 1)).flatMap(_ => ZStream.fromChunk(Payload))
            exit  <- source.run(fixture.store.put()).exit
            count <- pulls.get
          yield assertTrue(
            count == 0,
            exit.foldExit(_.failureOption.exists(_.isInstanceOf[StoreError.MissingTenantContext]), _ => false),
          )
        }
      },
      test("unknown tenant fails closed") {
        withTopology(shared = false) { fixture =>
          fixture.context.locally(Unknown)(fixture.store.healthCheck).exit.map { exit =>
            assertTrue(exit.foldExit(_.failureOption.exists(_.isInstanceOf[StoreError.TenantNotConfigured]), _ => false))
          }
        }
      },
      test("isolated tenants do not reuse physical blocks") {
        withTopology(shared = false) { fixture =>
          for
            first  <- fixture.context.locally(TenantA)(upload(fixture.store, Payload))
            second <- fixture.context.locally(TenantB)(upload(fixture.store, Payload))
          yield assertTrue(
            first.key == second.key,
            first.stats.freshBlocks > 0,
            first.stats.duplicateBlocks == 0,
            second.stats.freshBlocks > 0,
            second.stats.duplicateBlocks == 0,
          )
        }
      },
      test("shared domains reuse blocks but keep manifests tenant scoped") {
        withTopology(shared = true) { fixture =>
          for
            first       <- fixture.context.locally(TenantA)(upload(fixture.store, Payload))
            beforeShare <- fixture.context.locally(TenantB)(fixture.store.get(first.key).runDrain.either)
            second      <- fixture.context.locally(TenantB)(upload(fixture.store, Payload))
            read        <- fixture.context.locally(TenantB)(BoundedByteStream.collectInMemory(fixture.store.get(second.key)))
            countA      <- fixture.context.locally(TenantA)(fixture.store.streamInventory.runFold(0)((count, _) => count + 1))
            countB      <- fixture.context.locally(TenantB)(fixture.store.streamInventory.runFold(0)((count, _) => count + 1))
          yield assertTrue(
            beforeShare.isLeft,
            first.key == second.key,
            second.stats.freshBlocks == 0,
            second.stats.duplicateBlocks == second.stats.blockCount,
            read == Payload,
            countA == 1,
            countB == 1,
          )
        }
      },
      test("tenant context inherits into children and never joins back") {
        ZIO.serviceWithZIO[TenantContext] { context =>
          context.locally(TenantA) {
            for
              inherited  <- context.current.fork.flatMap(_.join)
              childSeen  <- context.locally(TenantB)(context.current).fork.flatMap(_.join)
              parentSeen <- context.current
            yield assertTrue(inherited == TenantA, childSeen == TenantB, parentSeen == TenantA)
          }
        }
      },
      test("tenant context isolates sibling fibers and restores after interruption") {
        ZIO.serviceWithZIO[TenantContext] { context =>
          context.locally(TenantA) {
            for
              siblings <- ZIO.foreachPar(Chunk(TenantA, TenantB))(tenant => context.locally(tenant)(ZIO.yieldNow *> context.current))
              child    <- context.locally(TenantB)(ZIO.never).fork
              _        <- child.interrupt
              restored <- context.current
            yield assertTrue(siblings == Chunk(TenantA, TenantB), restored == TenantA)
          }
        }
      },
      test("router resolves once per logical stream operation") {
        withTopology(shared = true) { fixture =>
          for
            resolutions <- Ref.make(0)
            counting     = TenantStoreProvider.fromFunction(tenant => resolutions.update(_ + 1) *> fixture.provider.resolve(tenant))
            routed       = new ContextualTenantBlobStore(counting, fixture.context)
            chunk        = Chunk.fill(64 * 1024)(0x5a.toByte)
            source       = ZStream.fromIterable(0 until 128).flatMap(_ => ZStream.fromChunk(chunk))
            written     <- fixture.context.locally(TenantA)(Chunker.locally(Chunker.fixed(Chunk64))(source.run(routed.put())))
            bytes       <- fixture.context.locally(TenantA)(routed.get(written.key).runCount)
            observed    <- resolutions.get
          yield assertTrue(bytes == 8L * 1024L * 1024L, observed == 2)
        }
      },
      test("topology rejects accidental block sharing across isolated domains") {
        for
          sharedBlock  <- InMemoryBlockStore.make
          manifestA    <- InMemoryBlobManifestRepo.make
          manifestB    <- InMemoryBlobManifestRepo.make
          coordinatorA <- MaintenanceCoordinator.inProcess().orDie
          coordinatorB <- MaintenanceCoordinator.inProcess().orDie
          budget       <- TransferBudget.make(TransferMemoryConfig.Default)
          routeA        = TenantRoute(TenantA)
          routeB        = TenantRoute(TenantB)
          result        = TenantStoreTopology.build(
                            Chunk(routeA, routeB),
                            Map(routeA.storageDomain -> sharedBlock, routeB.storageDomain  -> sharedBlock),
                            Map(TenantA              -> manifestA, TenantB                 -> manifestB),
                            Map(routeA.storageDomain -> coordinatorA, routeB.storageDomain -> coordinatorB),
                            budget,
                          )
        yield assertTrue(result.left.exists(_.isInstanceOf[TenantTopologyError.BlockStoreSharedAcrossDomains]))
      },
      test("topology rejects shared manifest repositories") {
        for
          blockA       <- InMemoryBlockStore.make
          blockB       <- InMemoryBlockStore.make
          manifest     <- InMemoryBlobManifestRepo.make
          coordinatorA <- MaintenanceCoordinator.inProcess().orDie
          coordinatorB <- MaintenanceCoordinator.inProcess().orDie
          budget       <- TransferBudget.make(TransferMemoryConfig.Default)
          routeA        = TenantRoute(TenantA)
          routeB        = TenantRoute(TenantB)
          result        = TenantStoreTopology.build(
                            Chunk(routeA, routeB),
                            Map(routeA.storageDomain -> blockA, routeB.storageDomain       -> blockB),
                            Map(TenantA              -> manifest, TenantB                  -> manifest),
                            Map(routeA.storageDomain -> coordinatorA, routeB.storageDomain -> coordinatorB),
                            budget,
                          )
        yield assertTrue(result.left.exists(_.isInstanceOf[TenantTopologyError.ManifestRepositorySharedAcrossTenants]))
      },
      test("topology rejects shared deduplication unless policy explicitly enables it") {
        for
          block       <- InMemoryBlockStore.make
          manifestA   <- InMemoryBlobManifestRepo.make
          manifestB   <- InMemoryBlobManifestRepo.make
          coordinator <- MaintenanceCoordinator.inProcess().orDie
          budget      <- TransferBudget.make(TransferMemoryConfig.Default)
          routeA       = TenantRoute(TenantA, DeduplicationScope.Shared(Shared))
          routeB       = TenantRoute(TenantB, DeduplicationScope.Shared(Shared))
          result       = TenantStoreTopology.build(
                           Chunk(routeA, routeB),
                           Map(routeA.storageDomain -> block),
                           Map(TenantA              -> manifestA, TenantB -> manifestB),
                           Map(routeA.storageDomain -> coordinator),
                           budget,
                         )
        yield assertTrue(result.left.exists(_.isInstanceOf[TenantTopologyError.SharedDeduplicationDisabled]))
      },
      test("tenant routing metrics use bounded labels and never tenant identifiers") {
        withTopology(shared = true) { fixture =>
          for
            metrics  <- InMemoryMetricsRegistry.make
            routed    = new ContextualTenantBlobStore(fixture.provider, fixture.context, metrics)
            _        <- fixture.context.locally(TenantA)(routed.healthCheck)
            _        <- fixture.context.locally(Unknown)(routed.healthCheck).either
            snapshot <- metrics.snapshot
            keys      = snapshot.counters.keys.filter(_.name == MetricKeys.TenantStoreResolutionsTotal).toVector
          yield assertTrue(
            keys.map(_.tags.get("outcome")).toSet == Set(Some("resolved"), Some("unknown_tenant")),
            keys.forall(key => !key.tags.contains("tenant") && !key.tags.values.exists(_.contains(TenantA.value))),
          )
        }
      },
    ).provideLayerShared(TenantContext.live) @@ TestAspect.sequential

  private final case class Fixture(
    store: BlobStore,
    provider: TenantStoreProvider,
    context: TenantContext,
  )

  private def withTopology[A](shared: Boolean)(use: Fixture => ZIO[Any, Any, A]): ZIO[TenantContext, Any, A] =
    for
      context      <- ZIO.service[TenantContext]
      blockA       <- InMemoryBlockStore.make
      blockB       <- InMemoryBlockStore.make
      manifestA    <- InMemoryBlobManifestRepo.make
      manifestB    <- InMemoryBlobManifestRepo.make
      budget       <- TransferBudget.make(TransferMemoryConfig.Default)
      routeA        = TenantRoute(TenantA, if shared then DeduplicationScope.Shared(Shared) else DeduplicationScope.Isolated)
      routeB        = TenantRoute(TenantB, if shared then DeduplicationScope.Shared(Shared) else DeduplicationScope.Isolated)
      domains       = Chunk(routeA.storageDomain, routeB.storageDomain).distinct
      coordinators <- ZIO.foreach(domains)(domain => MaintenanceCoordinator.inProcess().orDie.map(domain -> _)).map(_.toMap)
      blocks        =
        if shared then Map(routeA.storageDomain -> blockA)
        else Map(routeA.storageDomain           -> blockA, routeB.storageDomain -> blockB)
      provider     <- ZIO
                        .fromEither(
                          TenantStoreTopology.build(
                            Chunk(routeA, routeB),
                            blocks,
                            Map(TenantA -> manifestA, TenantB -> manifestB),
                            coordinators,
                            budget,
                            persistenceConfig = BlockPersistenceConfig.default,
                            tenantStorageConfig = TenantStorageConfig(allowSharedDeduplication = shared),
                          )
                        )
                        .orDie
      store         = new ContextualTenantBlobStore(provider, context)
      result       <- Chunker.locally(Chunker.fixed(Chunk64))(use(Fixture(store, provider, context)))
    yield result

  private def upload(store: BlobStore, payload: Chunk[Byte]) =
    ZStream.fromChunk(payload).run(store.put())
