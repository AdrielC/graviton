package graviton.runtime.tenant

import graviton.core.RefinedTypeExt
import graviton.core.types.{BlobOffset, FileSize, IdentifierConstraint}
import graviton.runtime.config.{BlockPersistenceConfig, TenantStorageConfig}
import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import graviton.runtime.model.{BlobDescription, BlobListing, BlobStat, BlobWritePlan, InventoryCursor, InventoryPage, InventoryPageSize}
import graviton.runtime.stores.*
import graviton.runtime.upload.TenantId
import io.github.iltotore.iron.constraint.collection.MaxLength
import zio.*
import zio.stream.*

/** Explicit opt-in name for a group allowed to share physical CAS blocks. */
type DeduplicationDomainId = DeduplicationDomainId.T
object DeduplicationDomainId extends RefinedTypeExt[String, IdentifierConstraint & MaxLength[120]]

/** Physical block and maintenance-coordination boundary. */
type StorageDomainId = StorageDomainId.T
object StorageDomainId extends RefinedTypeExt[String, IdentifierConstraint]:
  private[tenant] def isolated(tenantId: TenantId): StorageDomainId =
    applyUnsafe(s"tenant:${tenantId.value}")

  private[tenant] def shared(domainId: DeduplicationDomainId): StorageDomainId =
    applyUnsafe(s"shared:${domainId.value}")

/**
 * Controls whether a tenant receives a private physical block namespace or
 * explicitly joins a byte-sharing group. Isolation is the safe default.
 */
enum DeduplicationScope:
  case Isolated
  case Shared(domainId: DeduplicationDomainId)

  def storageDomain(tenantId: TenantId): StorageDomainId = this match
    case Isolated         => StorageDomainId.isolated(tenantId)
    case Shared(domainId) => StorageDomainId.shared(domainId)

final case class TenantRoute(
  tenantId: TenantId,
  deduplication: DeduplicationScope = DeduplicationScope.Isolated,
):
  val storageDomain: StorageDomainId = deduplication.storageDomain(tenantId)

final case class TenantStoreBinding(
  route: TenantRoute,
  store: BlobStore,
)

sealed abstract class TenantRoutingError(message: String) extends Exception(message)
object TenantRoutingError:
  case object MissingContext                         extends TenantRoutingError("no tenant context is active in this fiber")
  final case class UnknownTenant(tenantId: TenantId) extends TenantRoutingError(s"tenant ${tenantId.value} is not configured")

sealed abstract class TenantTopologyError(message: String) extends Exception(message)
object TenantTopologyError:
  case object Empty                                    extends TenantTopologyError("at least one tenant route is required")
  final case class DuplicateTenant(tenantId: TenantId) extends TenantTopologyError(s"tenant ${tenantId.value} is configured more than once")
  final case class MissingBlockStore(domainId: StorageDomainId)
      extends TenantTopologyError(s"storage domain ${domainId.value} has no block store")
  final case class MissingCoordinator(domainId: StorageDomainId)
      extends TenantTopologyError(s"storage domain ${domainId.value} has no maintenance coordinator")
  final case class MissingManifestRepository(tenantId: TenantId)
      extends TenantTopologyError(s"tenant ${tenantId.value} has no manifest repository")
  final case class UnexpectedBlockStore(domainId: StorageDomainId)
      extends TenantTopologyError(s"block store ${domainId.value} is not referenced by a tenant route")
  final case class UnexpectedCoordinator(domainId: StorageDomainId)
      extends TenantTopologyError(s"maintenance coordinator ${domainId.value} is not referenced by a tenant route")
  final case class UnexpectedManifestRepository(tenantId: TenantId)
      extends TenantTopologyError(s"manifest repository ${tenantId.value} is not referenced by a tenant route")
  final case class BlockStoreSharedAcrossDomains(left: StorageDomainId, right: StorageDomainId)
      extends TenantTopologyError(
        s"one block-store instance is assigned to distinct storage domains ${left.value} and ${right.value}"
      )
  final case class ManifestRepositorySharedAcrossTenants(left: TenantId, right: TenantId)
      extends TenantTopologyError(
        s"one manifest-repository instance is assigned to distinct tenants ${left.value} and ${right.value}"
      )
  final case class SharedDeduplicationDisabled(domainId: DeduplicationDomainId)
      extends TenantTopologyError(
        s"shared deduplication domain ${domainId.value} is disabled by tenant-storage policy"
      )

/** Resolve one long-lived logical store for a validated tenant identity. */
trait TenantStoreProvider:
  def resolve(tenantId: TenantId): IO[TenantRoutingError, TenantStoreBinding]

object TenantStoreProvider:
  def fromFunction(
    resolveTenant: TenantId => IO[TenantRoutingError, TenantStoreBinding]
  ): TenantStoreProvider =
    new TenantStoreProvider:
      override def resolve(tenantId: TenantId): IO[TenantRoutingError, TenantStoreBinding] =
        resolveTenant(tenantId)

  def static(bindings: Chunk[TenantStoreBinding]): Either[TenantTopologyError, TenantStoreProvider] =
    val duplicates = bindings.groupBy(_.route.tenantId).collectFirst { case (tenantId, values) if values.lengthCompare(1) > 0 => tenantId }
    duplicates match
      case Some(tenantId) => Left(TenantTopologyError.DuplicateTenant(tenantId))
      case None           =>
        val byTenant = bindings.map(binding => binding.route.tenantId -> binding).toMap
        Right(fromFunction(tenantId => ZIO.fromOption(byTenant.get(tenantId)).orElseFail(TenantRoutingError.UnknownTenant(tenantId))))

/**
 * Fiber-local tenant identity established only after authentication and policy
 * validation. Children inherit the tenant; child completion never overwrites
 * the parent's active tenant.
 */
trait TenantContext:
  def current: IO[TenantRoutingError.MissingContext.type, TenantId]
  def locally[R, E, A](tenantId: TenantId)(effect: ZIO[R, E, A]): ZIO[R, E, A]

object TenantContext:
  val current: ZIO[TenantContext, TenantRoutingError.MissingContext.type, TenantId] =
    ZIO.serviceWithZIO[TenantContext](_.current)

  def locally[R, E, A](tenantId: TenantId)(effect: ZIO[R, E, A]): ZIO[R & TenantContext, E, A] =
    ZIO.serviceWithZIO[TenantContext](_.locally(tenantId)(effect))

  val live: ULayer[TenantContext] =
    ZLayer.scoped {
      FiberRef
        .make[Option[TenantId]](
          initial = None,
          fork = identity,
          join = (parent, _) => parent,
        )
        .map { ref =>
          new TenantContext:
            override def current: IO[TenantRoutingError.MissingContext.type, TenantId] =
              ref.get.someOrFail(TenantRoutingError.MissingContext)

            override def locally[R, E, A](tenantId: TenantId)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
              ref.locally(Some(tenantId))(effect)
        }
    }

/**
 * Build tenant stores from explicit physical domains.
 *
 * A domain owns exactly one block store and maintenance coordinator. A tenant
 * owns exactly one manifest repository. Reference-identity checks reject the
 * most dangerous wiring mistakes: sharing blocks across different domains or
 * sharing manifests across tenants. One process-wide transfer budget keeps
 * aggregate buffering bounded across every tenant.
 */
object TenantStoreTopology:
  def build(
    routes: Chunk[TenantRoute],
    blockStores: Map[StorageDomainId, BlockStore],
    manifests: Map[TenantId, BlobManifestRepo],
    coordinators: Map[StorageDomainId, MaintenanceCoordinator],
    transferBudget: TransferBudget,
    metrics: MetricsRegistry = MetricsRegistry.noop,
    ingestConfig: CasBlobStore.IngestConfig = CasBlobStore.IngestConfig(),
    persistenceConfig: BlockPersistenceConfig = BlockPersistenceConfig.default,
    tenantStorageConfig: TenantStorageConfig = TenantStorageConfig.Default,
  ): Either[TenantTopologyError, TenantStoreProvider] =
    for
      _        <- Either.cond(routes.nonEmpty, (), TenantTopologyError.Empty)
      _        <- routes
                    .groupBy(_.tenantId)
                    .collectFirst { case (tenantId, values) if values.lengthCompare(1) > 0 => TenantTopologyError.DuplicateTenant(tenantId) }
                    .toLeft(())
      domains   = routes.map(_.storageDomain).toSet
      tenants   = routes.map(_.tenantId).toSet
      _        <- routes
                    .collectFirst {
                      case TenantRoute(_, DeduplicationScope.Shared(domainId)) if !tenantStorageConfig.allowSharedDeduplication =>
                        TenantTopologyError.SharedDeduplicationDisabled(domainId)
                    }
                    .toLeft(())
      _        <- domains.collectFirst { case domain if !blockStores.contains(domain) => TenantTopologyError.MissingBlockStore(domain) }.toLeft(())
      _        <-
        domains.collectFirst { case domain if !coordinators.contains(domain) => TenantTopologyError.MissingCoordinator(domain) }.toLeft(())
      _        <- tenants
                    .collectFirst { case tenant if !manifests.contains(tenant) => TenantTopologyError.MissingManifestRepository(tenant) }
                    .toLeft(())
      _        <- blockStores.keySet
                    .collectFirst { case domain if !domains.contains(domain) => TenantTopologyError.UnexpectedBlockStore(domain) }
                    .toLeft(())
      _        <- coordinators.keySet
                    .collectFirst { case domain if !domains.contains(domain) => TenantTopologyError.UnexpectedCoordinator(domain) }
                    .toLeft(())
      _        <- manifests.keySet
                    .collectFirst { case tenant if !tenants.contains(tenant) => TenantTopologyError.UnexpectedManifestRepository(tenant) }
                    .toLeft(())
      _        <- sharedBlockStoreReference(blockStores).toLeft(())
      _        <- sharedManifestReference(manifests).toLeft(())
      bindings  = routes.map { route =>
                    val raw = new CasBlobStore(
                      blockStores(route.storageDomain),
                      manifests(route.tenantId),
                      metrics = metrics,
                      ingestConfig = ingestConfig,
                      persistenceConfig = persistenceConfig,
                      transferBudget = transferBudget,
                    )
                    TenantStoreBinding(route, new CoordinatedBlobStore(raw, coordinators(route.storageDomain)))
                  }
      provider <- TenantStoreProvider.static(bindings)
    yield provider

  private def sharedBlockStoreReference(values: Map[StorageDomainId, BlockStore]): Option[TenantTopologyError] =
    values.toVector.combinations(2).collectFirst {
      case Vector((leftDomain, left), (rightDomain, right)) if left eq right =>
        TenantTopologyError.BlockStoreSharedAcrossDomains(leftDomain, rightDomain)
    }

  private def sharedManifestReference(values: Map[TenantId, BlobManifestRepo]): Option[TenantTopologyError] =
    values.toVector.combinations(2).collectFirst {
      case Vector((leftTenant, left), (rightTenant, right)) if left eq right =>
        TenantTopologyError.ManifestRepositorySharedAcrossTenants(leftTenant, rightTenant)
    }

/**
 * Ordinary [[BlobStore]] facade for protocol code running inside a validated
 * [[TenantContext]]. The provider is resolved once when an operation starts;
 * no tenant lookup occurs in the per-byte or per-block hot path.
 */
final class ContextualTenantBlobStore(
  provider: TenantStoreProvider,
  context: TenantContext,
  metrics: MetricsRegistry = MetricsRegistry.noop,
) extends BlobStore:

  override def put(plan: BlobWritePlan = BlobWritePlan()): BlobSink =
    ZSink.unwrap(resolve(StoreOperation.PutBlob).map(_.put(plan)))

  override def get(key: graviton.core.keys.BinaryKey.Blob): ZStream[Any, StoreError, Byte] =
    ZStream.unwrap(resolve(StoreOperation.GetBlob).map(_.get(key)))

  override def getRange(
    key: graviton.core.keys.BinaryKey.Blob,
    start: BlobOffset,
    length: FileSize,
  ): ZStream[Any, StoreError, Byte] =
    ZStream.unwrap(resolve(StoreOperation.GetRange).map(_.getRange(key, start, length)))

  override def stat(key: graviton.core.keys.BinaryKey.Blob): IO[StoreError, Option[BlobStat]] =
    resolve(StoreOperation.StatBlob).flatMap(_.stat(key))

  override def inventoryPage(after: Option[InventoryCursor], limit: InventoryPageSize): IO[StoreError, InventoryPage[BlobListing]] =
    resolve(StoreOperation.Inventory).flatMap(_.inventoryPage(after, limit))

  override def inspect(key: graviton.core.keys.BinaryKey.Blob): IO[StoreError, Option[BlobDescription]] =
    resolve(StoreOperation.InspectBlob).flatMap(_.inspect(key))

  override def delete(key: graviton.core.keys.BinaryKey.Blob): IO[StoreError, Unit] =
    resolve(StoreOperation.DeleteBlob).flatMap(_.delete(key))

  override def healthCheck: IO[StoreError, Unit] =
    resolve(StoreOperation.HealthCheck).flatMap(_.healthCheck)

  private def resolve(operation: StoreOperation): IO[StoreError, BlobStore] =
    Clock.nanoTime.flatMap { started =>
      context.current
        .mapError(_ => StoreError.MissingTenantContext(operation))
        .flatMap(tenantId =>
          provider
            .resolve(tenantId)
            .mapError {
              case TenantRoutingError.MissingContext          => StoreError.MissingTenantContext(operation)
              case TenantRoutingError.UnknownTenant(tenantId) => StoreError.TenantNotConfigured(operation, tenantId)
            }
        )
        .tapBoth(
          error => metrics.counter(MetricKeys.TenantStoreResolutionsTotal, resolutionTags(operation, error)),
          binding =>
            metrics.counter(
              MetricKeys.TenantStoreResolutionsTotal,
              Map(
                "operation" -> operation.toString,
                "outcome"   -> "resolved",
                "scope"     -> (binding.route.deduplication match
                  case DeduplicationScope.Isolated  => "isolated"
                  case DeduplicationScope.Shared(_) => "shared"),
              ),
            ),
        )
        .map(_.store)
        .ensuring(
          Clock.nanoTime.flatMap(finished =>
            metrics.histogram(
              MetricKeys.TenantStoreResolutionDuration,
              (finished - started).toDouble / 1_000_000_000d,
              Map("operation" -> operation.toString),
            )
          )
        )
    }

  private def resolutionTags(operation: StoreOperation, error: StoreError): Map[String, String] =
    val outcome = error match
      case _: StoreError.MissingTenantContext => "missing_context"
      case _: StoreError.TenantNotConfigured  => "unknown_tenant"
      case _                                  => "failed"
    Map("operation" -> operation.toString, "outcome" -> outcome, "scope" -> "unresolved")

object ContextualTenantBlobStore:
  val layer: ZLayer[TenantStoreProvider & TenantContext & MetricsRegistry, Nothing, BlobStore] =
    ZLayer.fromFunction((provider: TenantStoreProvider, context: TenantContext, metrics: MetricsRegistry) =>
      new ContextualTenantBlobStore(provider, context, metrics): BlobStore
    )

  val layerWithoutMetrics: ZLayer[TenantStoreProvider & TenantContext, Nothing, BlobStore] =
    (ZLayer.service[TenantStoreProvider] ++ ZLayer.service[TenantContext] ++ ZLayer.succeed(MetricsRegistry.noop)) >>> layer
