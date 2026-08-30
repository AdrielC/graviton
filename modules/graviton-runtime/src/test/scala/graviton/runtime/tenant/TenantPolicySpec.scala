package graviton.runtime.tenant

import graviton.core.types.{FileSize, UploadChunkSize}
import graviton.runtime.stores.*
import graviton.runtime.upload.TenantId
import graviton.streams.Chunker
import zio.*
import zio.stream.ZStream
import zio.test.*

object TenantPolicySpec extends ZIOSpecDefault:
  private val TenantA = TenantId.applyUnsafe("00000000-0000-4000-8000-000000000a01")

  private def policy(
    concurrency: Int = 1,
    maximumBytes: Long = 1024L,
    revision: Long = 1L,
    lifecycle: TenantLifecycle = TenantLifecycle.Active,
  ): TenantPolicy =
    TenantPolicy(
      TenantRoute(TenantA),
      lifecycle,
      TenantConcurrencyLimit.applyUnsafe(concurrency),
      FileSize.unsafe(maximumBytes),
      TenantRetainedBytesLimit.applyUnsafe(1024L * 1024L),
      TenantPolicyRevision.applyUnsafe(revision),
    )

  override def spec =
    suite("tenant policy and admission")(
      test("policy cache is bounded by time and refreshes the durable revision") {
        for
          revision <- Ref.make(1L)
          reads    <- Ref.make(0)
          source    = TenantPolicyCatalog.fromFunction(_ => reads.update(_ + 1) *> revision.get.map(value => policy(revision = value)))
          cached   <- TenantPolicyCatalog.cached(source, maximumEntries = 2, timeToLive = 1.second)
          first    <- cached.resolve(TenantA)
          second   <- cached.resolve(TenantA)
          before   <- reads.get
          _        <- revision.set(2L)
          _        <- TestClock.adjust(2.seconds)
          third    <- cached.resolve(TenantA)
          after    <- reads.get
        yield assertTrue(first.revision.value == 1L, second.revision.value == 1L, before == 1, third.revision.value == 2L, after == 2)
      },
      test("suspended policy fails closed and is not cached as active") {
        for
          source <- Ref.make(policy(lifecycle = TenantLifecycle.Suspended))
          reads  <- Ref.make(0)
          catalog = TenantPolicyCatalog.fromFunction(_ => reads.update(_ + 1) *> source.get)
          cached <- TenantPolicyCatalog.cached(catalog, maximumEntries = 1, timeToLive = 1.minute)
          first  <- cached.resolve(TenantA).exit
          _      <- source.set(policy(lifecycle = TenantLifecycle.Active, revision = 2L))
          second <- cached.resolve(TenantA)
          count  <- reads.get
        yield assertTrue(
          first.foldExit(_.failureOption.exists(_.isInstanceOf[TenantRoutingError.SuspendedTenant]), _ => false),
          second.lifecycle == TenantLifecycle.Active,
          count == 2,
        )
      },
      test("concurrent cold reads share one durable policy lookup") {
        for
          reads   <- Ref.make(0)
          started <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          source   = TenantPolicyCatalog.fromFunction(_ => reads.update(_ + 1) *> started.succeed(()) *> release.await.as(policy()))
          cached  <- TenantPolicyCatalog.cached(source, maximumEntries = 1, timeToLive = 1.minute)
          fibers  <- ZIO.foreach(1 to 128)(_ => cached.resolve(TenantA).fork)
          _       <- started.await
          _       <- release.succeed(())
          results <- ZIO.foreach(fibers)(_.join)
          count   <- reads.get
        yield assertTrue(results.length == 128, results.forall(_.route.tenantId == TenantA), count == 1)
      },
      test("one slow cold lookup does not convoy another tenant in the same shard") {
        val tenantB = TenantId.applyUnsafe("00000000-0000-4000-8000-000000000b01")
        for
          slowStarted <- Promise.make[Nothing, Unit]
          releaseSlow <- Promise.make[Nothing, Unit]
          source       = TenantPolicyCatalog.fromFunction {
                           case TenantA => slowStarted.succeed(()) *> releaseSlow.await.as(policy())
                           case other   => ZIO.succeed(policy().copy(route = TenantRoute(other)))
                         }
          cached      <- TenantPolicyCatalog.cached(source, maximumEntries = 1, timeToLive = 1.minute)
          slow        <- cached.resolve(TenantA).fork
          _           <- slowStarted.await
          independent <- cached.resolve(tenantB).timeoutFail(new IllegalStateException("tenant lookup convoyed"))(1.second)
          _           <- releaseSlow.succeed(())
          _           <- slow.join
        yield assertTrue(independent.route.tenantId == tenantB)
      } @@ TestAspect.withLiveClock,
      test("concurrency wait times out and interruption releases the tenant entry") {
        for
          admission <- TenantAdmission.make(maximumResidentTenants = 1, acquisitionTimeout = 1.second)
          entered   <- Promise.make[Nothing, Unit]
          first     <- ZIO.scoped(admission.acquireScoped(policy()) *> entered.succeed(()) *> ZIO.never).fork
          _         <- entered.await
          second    <- ZIO.scoped(admission.acquireScoped(policy())).fork
          _         <- TestClock.adjust(2.seconds)
          denied    <- second.join.exit
          _         <- first.interrupt
          admitted  <- ZIO.scoped(admission.acquireScoped(policy())).exit
          residents <- admission.residentTenants
        yield assertTrue(
          denied.foldExit(_.failureOption.exists(_.isInstanceOf[TenantAdmission.Error.TimedOut]), _ => false),
          admitted.isSuccess,
          residents == 1,
        )
      },
      test("registry never evicts an active tenant to admit another tenant") {
        val tenantB = TenantId.applyUnsafe("00000000-0000-4000-8000-000000000b01")
        val policyB = policy().copy(route = TenantRoute(tenantB))
        for
          admission <- TenantAdmission.make(maximumResidentTenants = 1, acquisitionTimeout = 1.second)
          entered   <- Promise.make[Nothing, Unit]
          first     <- ZIO.scoped(admission.acquireScoped(policy()) *> entered.succeed(()) *> ZIO.never).fork
          _         <- entered.await
          denied    <- ZIO.scoped(admission.acquireScoped(policyB)).exit
          _         <- first.interrupt
          accepted  <- ZIO.scoped(admission.acquireScoped(policyB)).exit
        yield assertTrue(
          denied.foldExit(_.failureOption.exists(_.isInstanceOf[TenantAdmission.Error.Saturated]), _ => false),
          accepted.isSuccess,
        )
      },
      test("a tighter policy fails new admission until old permits drain") {
        val original  = policy(concurrency = 2, revision = 1L)
        val tightened = policy(concurrency = 1, revision = 2L)
        for
          admission <- TenantAdmission.make(maximumResidentTenants = 1, acquisitionTimeout = 1.second)
          entered   <- Promise.make[Nothing, Unit]
          active    <- ZIO.scoped(admission.acquireScoped(original) *> entered.succeed(()) *> ZIO.never).fork
          _         <- entered.await
          changing  <- ZIO.scoped(admission.acquireScoped(tightened)).exit
          _         <- active.interrupt
          accepted  <- ZIO.scoped(admission.acquireScoped(tightened)).exit
        yield assertTrue(
          changing.foldExit(_.failureOption.exists(_.isInstanceOf[TenantAdmission.Error.PolicyChanging]), _ => false),
          accepted.isSuccess,
        )
      },
      test("object ceiling rejects a streaming upload without collecting it") {
        for
          blockStore <- InMemoryBlockStore.make
          manifests  <- InMemoryBlobManifestRepo.make
          admission  <- TenantAdmission.make(maximumResidentTenants = 1, acquisitionTimeout = 1.second)
          underlying  = new CasBlobStore(blockStore, manifests)
          admitted    = new AdmittedTenantBlobStore(underlying, policy(maximumBytes = 1024L), admission)
          pulled     <- Ref.make(0L)
          source      = ZStream
                          .fromIterable(0 until 64)
                          .mapZIO(index => pulled.update(_ + 64L).as(Chunk.fill(64)((index & 0xff).toByte)))
                          .flattenChunks
          exit       <- Chunker.locally(Chunker.fixed(UploadChunkSize.applyUnsafe(256)))(source.run(admitted.put())).exit
          observed   <- pulled.get
          inventory  <- underlying.streamInventory.runCount
        yield assertTrue(
          exit.foldExit(_.failureOption.exists(_.isInstanceOf[StoreError.CapacityExceeded]), _ => false),
          observed <= 1088L,
          inventory == 0L,
        )
      },
      test("thousands of tenants resolve and admit through independent bounded shards") {
        val tenants  = Chunk.fromIterable((1 to 4096).map { index =>
          TenantId.applyUnsafe(f"00000000-0000-4000-8000-$index%012x")
        })
        val policies = tenants.map(tenantId => policy(concurrency = 4).copy(route = TenantRoute(tenantId)))

        for
          catalog0  <- ZIO.fromEither(TenantPolicyCatalog.static(policies))
          catalog   <- TenantPolicyCatalog.cached(catalog0, maximumEntries = 8192, timeToLive = 1.minute)
          blocks    <- InMemoryBlockStore.make
          manifests <- InMemoryBlobManifestRepo.make
          store      = new CasBlobStore(blocks, manifests)
          builds    <- Ref.make(0)
          provider  <- TenantStoreProvider.cached(catalog, maximumEntries = 8192)(_ => builds.update(_ + 1).as(store))
          first     <- ZIO.foreachPar(tenants)(provider.resolve)
          second    <- ZIO.foreachPar(tenants)(provider.resolve)
          built     <- builds.get
          admission <- TenantAdmission.make(maximumResidentTenants = 2048, acquisitionTimeout = 1.second)
          admitted  <- ZIO.foreachPar(policies.take(1024))(value => ZIO.scoped(admission.acquireScoped(value)).exit)
          residents <- admission.residentTenants
        yield assertTrue(
          first.length == tenants.length,
          second.length == tenants.length,
          built == tenants.length,
          admitted.forall(_.isSuccess),
          residents == 1024,
        )
      } @@ TestAspect.timeout(30.seconds),
    ) @@ TestAspect.sequential
