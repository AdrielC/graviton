package graviton.backend.laws

import graviton.runtime.config.{BlockPersistenceConfig, TenantStorageConfig, TransferMemoryConfig}
import graviton.runtime.stores.*
import graviton.runtime.tenant.*
import graviton.runtime.upload.TenantId
import zio.*
import zio.test.*

object TenantStorageLawsSpec extends ZIOSpecDefault:
  private val TenantA = TenantId.applyUnsafe("00000000-0000-4000-8000-000000000a01")
  private val TenantB = TenantId.applyUnsafe("00000000-0000-4000-8000-000000000b01")
  private val Unknown = TenantId.applyUnsafe("00000000-0000-4000-8000-000000000c01")
  private val Shared  = DeduplicationDomainId.applyUnsafe("shared-law-domain")

  override def spec =
    suite("published tenant storage laws")(
      TenantStorageLaws.suite("in-memory CAS")(fixture)
    )

  private val fixture: ZIO[Scope, StoreError, TenantStorageFixture] =
    for
      context  <- TenantContext.live.build.map(_.get[TenantContext])
      budget   <- TransferBudget.make(TransferMemoryConfig.Default)
      isolated <- buildStore(context, budget, shared = false)
      shared   <- buildStore(context, budget, shared = true)
    yield new TenantStorageFixture:
      override val tenantA: TenantId                                                         = TenantA
      override val tenantB: TenantId                                                         = TenantB
      override val unknownTenant: TenantId                                                   = Unknown
      override val isolatedStore: BlobStore                                                  = isolated
      override val sharedStore: BlobStore                                                    = shared
      override def asTenant[R, E, A](tenantId: TenantId)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
        context.locally(tenantId)(effect)
      override def withoutTenant[R, E, A](effect: ZIO[R, E, A]): ZIO[R, E, A]                =
        effect

  private def buildStore(
    context: TenantContext,
    budget: TransferBudget,
    shared: Boolean,
  ): IO[StoreError, BlobStore] =
    for
      blockA       <- InMemoryBlockStore.make
      blockB       <- InMemoryBlockStore.make
      manifestA    <- InMemoryBlobManifestRepo.make
      manifestB    <- InMemoryBlobManifestRepo.make
      routeA        = TenantRoute(TenantA, if shared then DeduplicationScope.Shared(Shared) else DeduplicationScope.Isolated)
      routeB        = TenantRoute(TenantB, if shared then DeduplicationScope.Shared(Shared) else DeduplicationScope.Isolated)
      domains       = Chunk(routeA.storageDomain, routeB.storageDomain).distinct
      coordinators <- ZIO.foreach(domains)(domain => MaintenanceCoordinator.inProcess().map(domain -> _)).map(_.toMap).mapError(lawInput)
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
                        .mapError(error => lawInput(new IllegalArgumentException(error.getMessage)))
    yield new ContextualTenantBlobStore(provider, context)

  private def lawInput(error: IllegalArgumentException): StoreError =
    StoreError.InvalidInput(StoreOperation.ResolveTenant, error.getMessage)
