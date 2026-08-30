package graviton.server

import graviton.backend.pg.{
  PgBlobManifestRepo,
  PgCatalog,
  PgDataSource,
  PgMaintenanceCoordinator,
  PgRepairJournal,
  PgResumableUploadRepository,
  PgTenantBlobManifestRepo,
  PgTenantPolicyCatalog,
}
import graviton.backend.s3.{
  S3BlockStore,
  S3BlockStoreConfig,
  S3ClientLayer,
  S3Config,
  S3ErasureFragmentStore,
  S3MutableObjectStore,
  S3ObjectStoreConfig,
}
import graviton.integration.shardcake.{ShardcakeNode, ShardcakeRegistrationConfig, ShardcakeUploadConfig}
import graviton.pdf.PdfUploadSupport
import graviton.protocol.http.{AuthMiddleware, BlobIngest, DevAuthRoutes, HttpApi, HttpSecurityPolicy, MetricsHttpApi, TenantHttpApi}
import graviton.protocol.grpc.{AuthInterceptor, CapabilityInterceptor, GravitonGrpcServer, GrpcServerConfig, RateLimitInterceptor}
import graviton.runtime.catalog.{Catalog, FsCatalog}
import graviton.runtime.config.{
  BlockPersistenceConfig,
  GravitonConfig,
  ManifestIntegrityConfig,
  MaintenanceConfig,
  ReplicaStorageMode,
  TenantDataPlaneConfig,
  TenantStorageConfig,
  TransferAdmissionConfig,
  TransferMemoryConfig,
}
import graviton.runtime.metrics.MetricsRegistry
import graviton.runtime.stores.{
  BlobManifestRepo,
  BlobStore,
  BlockTransferFootprint,
  BlockStore,
  CasBlobStore,
  CoordinatedBlobStore,
  ErasureBlockStore,
  FileMaintenanceCoordinator,
  FsBlobManifestRepo,
  FsBlockStore,
  FsMutableObjectStore,
  MaintenanceCoordinator,
  ManifestIntegrity,
  MetricsBlobStore,
  RepairableBlockStore,
  ReplicaPlacement,
  RepairJournal,
  ReplicaRepairService,
  ReplicatedBlockStore,
  FsRepairJournal,
  StoreBackend,
  TransferBudget,
  TransferScope,
}
import graviton.runtime.upload.{FsResumableUploadRepository, ResumableUploadService, UploadStagingTarget}
import graviton.runtime.tenant.*
import graviton.core.types.{RepositoryNamespace, UploadChunkSize}
import graviton.streams.Chunker
import graviton.security.*
import graviton.security.jwt.{HmacJwtVerifier, OidcJwtVerifier}
import graviton.shared.ApiModels.*
import graviton.shared.ApiJson
import graviton.server.console.{ConsoleApi, ConsoleConfig}
import graviton.server.metrics.ZioMetricsRegistry
import zio.*
import zio.http.*
import zio.metrics.connectors.MetricsConfig
import zio.metrics.connectors.prometheus
import zio.metrics.jvm.DefaultJvmMetrics
import zio.json.EncoderOps
import zio.json.ast.Json

import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import software.amazon.awssdk.services.s3.S3Client

object Main extends ZIOAppDefault:

  override def run: ZIO[Any, Any, Any] = ZIO.scoped {
    for
      cfg                                          <- ZIO.config(GravitonConfig.config)
      maintenance                                  <- ZIO.config(MaintenanceConfig.config)
      blockPersistence                             <- ZIO.config(BlockPersistenceConfig.config)
      transferMemory                               <- ZIO.config(TransferMemoryConfig.config)
      transferAdmission                            <- ZIO.config(TransferAdmissionConfig.config)
      transferBudget                               <- TransferBudget.make(transferMemory, transferAdmission)
      manifestIntegrityConfig                      <- ZIO.config(ManifestIntegrityConfig.config)
      manifestIntegrity                            <- manifestIntegrityConfig.build.mapError(new IllegalArgumentException(_))
      tenantDataPlaneConfig                        <- ZIO.config(TenantDataPlaneConfig.config)
      tenantStorageConfig                          <- ZIO.config(TenantStorageConfig.config)
      shardcake                                    <- ZIO.config(ShardcakeUploadConfig.config)
      console                                      <- ZIO.config(ConsoleConfig.config)
      healthConfig                                 <- ZIO.config(RuntimeHealth.Config.config)
      sec                                          <- ZIO.config(SecurityConfig.config)
      rateLimiterRegistry                          <- ZIO.config(RateLimiter.RegistryConfig.config)
      registration                                 <- ZIO.config(ShardcakeRegistrationConfig.config)
      _                                            <- ConfigurationValidation.validate(cfg, shardcake, registration, console, sec)
      _                                            <- validateSecurityOrFail(sec)
      _                                            <- validateShardcakeTopology(cfg, shardcake)
      _                                            <- validateConsoleSecurity(sec, console)
      _                                            <- validateTenantDataPlane(cfg, tenantDataPlaneConfig, sec)
      primaryDataSource                            <- ZIO.when(requiresPrimaryPostgres(cfg, tenantDataPlaneConfig, sec))(
                                                        PgDataSource.scopedFromEnv
                                                      )
      _                                            <- logSecurityPosture(sec)
      port                                          = cfg.httpPort
      started                                      <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _                                            <- ZIO.logInfo(s"Starting Graviton server on :$port")
      verifierOpt                                  <- buildVerifier(sec)
      program                                       = ZIO.scoped {
                                                        for
                                                          _                                  <- DefaultJvmMetrics.liveV2.build.unit
                                                          blobStore                          <- ZIO.service[BlobStore]
                                                          catalog                            <- ZIO.service[Catalog]
                                                          metrics                            <- ZIO.service[MetricsRegistry]
                                                          resumable                          <- ZIO.service[ResumableUploadService]
                                                          auditSink                          <- ZIO.service[AuditSink]
                                                          capabilityCheck                    <- ZIO.service[CapabilityCheck]
                                                          rateLimiter                        <- ZIO.service[RateLimiter]
                                                          _                                  <- ZIO.foreachDiscard(primaryDataSource)(dataSource =>
                                                                                                  PgDataSource.superviseMetrics(
                                                                                                    dataSource,
                                                                                                    metrics,
                                                                                                    "primary",
                                                                                                  )
                                                                                                )
                                                          tenantDataPlane                    <- ZIO.when(tenantDataPlaneConfig.enabled)(
                                                                                                  requiredDataSource(primaryDataSource).flatMap(dataSource =>
                                                                                                    buildTenantDataPlane(
                                                                                                      cfg,
                                                                                                      maintenance,
                                                                                                      blockPersistence,
                                                                                                      transferBudget,
                                                                                                      tenantDataPlaneConfig,
                                                                                                      tenantStorageConfig,
                                                                                                      metrics,
                                                                                                      dataSource,
                                                                                                      manifestIntegrity,
                                                                                                    )
                                                                                                  )
                                                                                                )
                                                          shardcakeNode                      <- buildShardcakeNode(
                                                                                                  shardcake,
                                                                                                  tenantDataPlane,
                                                                                                  blobStore,
                                                                                                  metrics,
                                                                                                )
                                                          runtimeHealth                       = RuntimeHealth.make(
                                                                                                  blobStore,
                                                                                                  resumable,
                                                                                                  shardcakeNode,
                                                                                                  metrics,
                                                                                                  healthConfig,
                                                                                                )
                                                          _                                  <- resumable.cleanupExpired
                                                                                                  .tapBoth(
                                                                                                    error => ZIO.logErrorCause("Resumable upload cleanup failed", Cause.fail(error)),
                                                                                                    count => ZIO.logInfo(s"Resumable upload cleanup removed $count expired sessions").when(count > 0L),
                                                                                                  )
                                                                                                  .ignore
                                                                                                  .repeat(Schedule.spaced(cfg.resumableUploads.cleanupInterval))
                                                                                                  .forkScoped
                                                          runtime                            <- ZIO.runtime[Any]
                                                          uploadIngestor                      = PdfUploadSupport.ingestor(blobStore)
                                                          interceptors                        = verifierOpt.toList.flatMap { verifier =>
                                                                                                  List(
                                                                                                    new AuthInterceptor(verifier, auditSink, runtime),
                                                                                                    new CapabilityInterceptor(capabilityCheck, runtime, Some(auditSink)),
                                                                                                    new RateLimitInterceptor(rateLimiter, runtime),
                                                                                                  )
                                                                                                }
                                                          grpc                               <- tenantDataPlane match
                                                                                                  case None         =>
                                                                                                    GravitonGrpcServer.scoped(
                                                                                                      blobStore,
                                                                                                      uploadIngestor,
                                                                                                      GrpcServerConfig(cfg.grpcPort),
                                                                                                      interceptors,
                                                                                                    )
                                                                                                  case Some(tenant) =>
                                                                                                    GravitonGrpcServer.scopedTenants(
                                                                                                      blobStore,
                                                                                                      tenant.provider,
                                                                                                      tenant.context,
                                                                                                      store => PdfUploadSupport.ingestor(store),
                                                                                                      GrpcServerConfig(cfg.grpcPort),
                                                                                                      interceptors,
                                                                                                    )
                                                          boundPort                          <- grpc.port
                                                          _                                  <- ZIO.logInfo(s"gRPC API listening on :$boundPort")
                                                          policy                              = Option.when(sec.enabled)(
                                                                                                  HttpSecurityPolicy.make(sec, capabilityCheck, rateLimiter, auditSink)
                                                                                                )
                                                          localizedUpload                     = shardcakeNode.map(_.locality)
                                                          metricsApi                          = Some(MetricsHttpApi(metrics, policy))
                                                          singleApi                           = HttpApi(
                                                                                                  blobStore = blobStore,
                                                                                                  metrics = metricsApi,
                                                                                                  security = policy,
                                                                                                  localizedUpload = localizedUpload,
                                                                                                  resumableUploads = Some(resumable),
                                                                                                )
                                                          tenantApi                           = tenantDataPlane.map(tenant =>
                                                                                                  new TenantHttpApi(
                                                                                                    tenant.provider,
                                                                                                    tenant.context,
                                                                                                    blobStore,
                                                                                                    metricsApi,
                                                                                                    policy,
                                                                                                    localizedUpload,
                                                                                                    Some(resumable),
                                                                                                  )
                                                                                                )
                                                          apiRoutes                           = tenantApi.fold(singleApi.routes)(_.routes)
                                                          apiPreflightRoutes                  = tenantApi.fold(singleApi.preflightRoutes)(_.preflightRoutes)
                                                          publicRoutes: Routes[Any, Nothing]  =
                                                            Routes(
                                                              Method.GET / "api" / "health"           -> Handler.fromZIO {
                                                                for
                                                                  now <- Clock.currentTime(TimeUnit.MILLISECONDS)
                                                                  up   = (now - started).max(0L)
                                                                yield Response.json(
                                                                  ApiJson.encode(
                                                                    HealthResponse(
                                                                      status = "ok",
                                                                      version = _root_.graviton.server.BuildInfo.version,
                                                                      uptime = up,
                                                                    )
                                                                  )
                                                                )
                                                              },
                                                              Method.GET / "api" / "health" / "live"  -> Handler.fromZIO {
                                                                for
                                                                  now <- Clock.currentTime(TimeUnit.MILLISECONDS)
                                                                  up   = (now - started).max(0L)
                                                                yield Response.json(
                                                                  ApiJson.encode(
                                                                    HealthResponse(
                                                                      status = "ok",
                                                                      version = _root_.graviton.server.BuildInfo.version,
                                                                      uptime = up,
                                                                    )
                                                                  )
                                                                )
                                                              },
                                                              Method.GET / "api" / "health" / "ready" -> Handler.fromZIO {
                                                                runtimeHealth.refresh
                                                                  .map {
                                                                    case snapshot if snapshot.ready =>
                                                                      Response.json(
                                                                        Json
                                                                          .Obj(
                                                                            "status"  -> Json.Str("ready"),
                                                                            "version" -> Json.Str(_root_.graviton.server.BuildInfo.version),
                                                                          )
                                                                          .toJson
                                                                      )
                                                                    case _                          =>
                                                                      Response
                                                                        .json(Json.Obj("status" -> Json.Str("not_ready")).toJson)
                                                                        .copy(status = Status.ServiceUnavailable)
                                                                  }
                                                              },
                                                            )

                                                          statsRoutes: Routes[Any, Nothing]   =
                                                            Routes(
                                                              Method.GET / "api" / "stats" -> Handler.fromFunctionZIO[Request] { request =>
                                                                val resource = ResourceRef(ResourceKind.Observability, None)
                                                                val response =
                                                                  metrics.snapshot.map(snapshot => Response.json(ApiJson.encode(RuntimeStats.from(snapshot))))
                                                                policy match
                                                                  case None         => response
                                                                  case Some(active) =>
                                                                    active
                                                                      .authorize(request, "observability.stats.read", Capability.ObservabilityRead, resource)
                                                                      .foldZIO(
                                                                        denied => ZIO.succeed(denied),
                                                                        _ =>
                                                                          response
                                                                            .flatMap(result => active.recordOutcome("observability.stats.read", resource, result).as(result)),
                                                                      )
                                                              }
                                                            )

                                                          appRoutes: Routes[Any, Nothing]     =
                                                            verifierOpt match
                                                              case Some(verifier) =>
                                                                val decorateFailure =
                                                                  (request: Request, response: Response) => policy.fold(response)(_.addCorsHeaders(request, response))
                                                                (apiRoutes ++ statsRoutes) @@ AuthMiddleware.required(
                                                                  verifier,
                                                                  auditSink,
                                                                  decorateFailure,
                                                                  sec.trustProxyHeaders,
                                                                )
                                                              case None           =>
                                                                apiRoutes ++ statsRoutes

                                                          devRoutes: Routes[Any, Nothing]     =
                                                            sec.devSharedSecret match
                                                              case Some(secret) if sec.enabled =>
                                                                DevAuthRoutes.routes(secret, sec.oidcIssuer, sec.oidcAudience)
                                                              case _                           =>
                                                                Routes.empty

                                                          consoleRoutes: Routes[Any, Nothing] =
                                                            if console.enabled then
                                                              ConsoleApi(
                                                                catalog,
                                                                blobStore,
                                                                BlobIngest.make(blobStore, localizedUpload),
                                                                Option.when(shardcakeNode.isDefined)(shardcake.node),
                                                                runtimeHealth,
                                                                _root_.graviton.server.BuildInfo.version,
                                                              ).routes
                                                            else Routes.empty
                                                          preflightRoutes                     = if sec.enabled then apiPreflightRoutes else Routes.empty
                                                          nonConsoleRoutes                    = publicRoutes ++ preflightRoutes ++ appRoutes ++ devRoutes
                                                          browserApiRoutes                    =
                                                            if sec.enabled then nonConsoleRoutes else Middleware.cors(nonConsoleRoutes)
                                                          routes                              = consoleRoutes ++ browserApiRoutes
                                                          _                                  <- Server.serve(routes)
                                                        yield ()
                                                      }

      auditLayer: ZLayer[Any, Throwable, AuditSink] = sec.auditBackend match
                                                        case "jdbc"   =>
                                                          ZLayer.fromZIO(requiredDataSource(primaryDataSource)) >>> AuditSink.jdbc
                                                        case "memory" =>
                                                          ZLayer.fromZIO[Any, Nothing, AuditSink](AuditSink.inMemory)
                                                        case other    =>
                                                          ZLayer.fail(
                                                            new IllegalArgumentException(
                                                              s"Unsupported GRAVITON_SECURITY_AUDIT_BACKEND='$other' (expected 'jdbc' or 'memory')"
                                                            )
                                                          )

      chunker = Chunker.fixed(UploadChunkSize.applyUnsafe(cfg.chunkSize))
      _      <- Chunker.locally(chunker) {
                  program.provide(
                    Server.defaultWith { server =>
                      val streaming = server.enableRequestStreaming
                      if console.enabled && !console.allowRemoteBinding then streaming.binding("127.0.0.1", port)
                      else streaming.port(port)
                    },
                    blobLayer(cfg, maintenance, blockPersistence, transferBudget, primaryDataSource, manifestIntegrity),
                    resumableUploadLayer(cfg, transferBudget, primaryDataSource),
                    catalogLayer(cfg, primaryDataSource),
                    auditLayer,
                    capabilityLayer(sec, primaryDataSource),
                    ZLayer.succeed(sec) >>> RateLimiter.configured(rateLimiterRegistry),
                    ZLayer.succeed(MetricsConfig(5.seconds)),
                    prometheus.publisherLayer,
                    prometheus.prometheusLayer,
                    ZioMetricsRegistry.layer,
                  )
                }
    yield ()
  }

  sealed trait ConfigurationError extends Exception

  object ConfigurationError:
    final case class ShardcakeRequiresSharedStorage(blobBackend: String)
        extends Exception(
          s"Shardcake upload locality requires the shared S3 plus PostgreSQL composition, not '$blobBackend'"
        )
        with ConfigurationError
    case object ConsoleRequiresOpenLocalMode
        extends Exception("GRAVITON_CONSOLE_ENABLED requires GRAVITON_SECURITY_ENABLED=false; do not expose the local console publicly")
        with ConfigurationError
    final case class InvalidTenantDataPlane(reason: String)
        extends Exception(s"invalid multi-tenant data plane configuration: $reason")
        with ConfigurationError

  private[server] def validateShardcakeTopology(
    cfg: GravitonConfig,
    shardcake: ShardcakeUploadConfig,
  ): IO[ConfigurationError, Unit] =
    ZIO
      .fail(ConfigurationError.ShardcakeRequiresSharedStorage(cfg.blobBackend))
      .when(shardcake.enabled && !Set("s3", "minio").contains(cfg.blobBackend.toLowerCase))
      .unit

  private[server] def validateConsoleSecurity(
    security: SecurityConfig,
    console: ConsoleConfig,
  ): IO[ConfigurationError, Unit] =
    ZIO.fail(ConfigurationError.ConsoleRequiresOpenLocalMode).when(console.enabled && security.enabled).unit

  private[server] def validateTenantDataPlane(
    config: GravitonConfig,
    tenant: TenantDataPlaneConfig,
    security: SecurityConfig,
  ): IO[ConfigurationError, Unit] =
    if !tenant.enabled then ZIO.unit
    else
      val checks = for
        _ <- tenant.validate
        _ <- Either.cond(security.enabled, (), "security must be enabled")
        _ <- Either.cond(security.requireTls, (), "TLS enforcement must be enabled")
        _ <- Either.cond(security.devSharedSecret.isEmpty, (), "development shared-secret authentication is forbidden")
        _ <- Either.cond(security.auditBackend == "jdbc", (), "durable JDBC audit is required")
        _ <- Either.cond(Set("s3", "minio").contains(config.blobBackend.toLowerCase), (), "S3, MinIO, or Ceph RGW storage is required")
        _ <- config.replication.validate
        _ <- Either.cond(
               !config.replication.enabled || config.replication.mode == ReplicaStorageMode.Replicated,
               (),
               "multi-tenant erasure coding requires a domain-aware repair inventory and is not enabled",
             )
        _ <- Either.cond(
               !config.replication.enabled ||
                 (config.replication.effectiveDesiredReplicas == config.replication.targets.length &&
                   config.replication.effectiveWriteQuorum == config.replication.targets.length),
               (),
               "multi-tenant replication requires every configured target in the placement and write quorum",
             )
      yield ()
      ZIO.fromEither(checks).mapError(ConfigurationError.InvalidTenantDataPlane.apply)

  private final case class TenantDataPlane(
    provider: TenantStoreProvider,
    context: TenantContext,
  )

  private trait TenantBlockStoreFactory:
    def make(domain: StorageDomainId): Task[BlockStore]

  private final case class TenantReplicaTarget(
    name: String,
    failureDomain: String,
    baseConfig: S3Config,
    client: S3Client,
  )

  private def buildTenantDataPlane(
    config: GravitonConfig,
    maintenance: MaintenanceConfig,
    persistence: BlockPersistenceConfig,
    transferBudget: TransferBudget,
    tenantConfig: TenantDataPlaneConfig,
    storageConfig: TenantStorageConfig,
    metrics: MetricsRegistry,
    dataSource: DataSource,
    manifestIntegrity: Option[ManifestIntegrity],
  ): ZIO[Scope, Throwable, TenantDataPlane] =
    for
      blockStores <- tenantBlockStoreFactory(config, metrics)
      rawCatalog   = new PgTenantPolicyCatalog(dataSource, storageConfig, tenantConfig.cellId)
      catalog     <- TenantPolicyCatalog.cached(rawCatalog, tenantConfig.maximumCachedTenants, tenantConfig.policyCacheTtl)
      admission   <- TenantAdmission.make(tenantConfig.maximumCachedTenants, tenantConfig.admissionTimeout, metrics)
      // One cell-wide coordinator coalesces every in-process operation onto a
      // single shared PostgreSQL advisory-lock session. This preserves the
      // backend-wide maintenance barrier without holding one pool connection
      // per active tenant. A maintenance lease intentionally drains the cell.
      coordinator <- PgMaintenanceCoordinator.make(
                       dataSource,
                       maintenance.copy(namespace = tenantCellMaintenanceNamespace(maintenance.namespace, tenantConfig.cellId)),
                     )
      contextEnv  <- TenantContext.live.build
      context      = contextEnv.get[TenantContext]
      provider    <- TenantStoreProvider.cached(catalog, tenantConfig.maximumCachedTenants) { policy =>
                       val route     = policy.route
                       val domain    = route.storageDomain
                       val manifests = manifestIntegrity.fold[PgTenantBlobManifestRepo](
                         new PgTenantBlobManifestRepo(dataSource, route.tenantId, domain)
                       )(integrity => PgTenantBlobManifestRepo.authenticated(dataSource, route.tenantId, domain, integrity))
                       (for blocks <- blockStores.make(domain)
                       yield
                         val scopedBudget = transferBudget.bind(
                           TransferScope(Some(route.tenantId), BlockTransferFootprint.backendOf(blocks))
                         )
                         val cas          = new CasBlobStore(
                           blocks,
                           manifests,
                           metrics = metrics,
                           persistenceConfig = persistence,
                           transferBudget = scopedBudget,
                         )
                         val coordinated  = new CoordinatedBlobStore(cas, coordinator)
                         val admitted     = new AdmittedTenantBlobStore(coordinated, policy, admission)
                         val scopeTag     = route.deduplication match
                           case DeduplicationScope.Isolated  => "isolated"
                           case DeduplicationScope.Shared(_) => "shared"
                         new MetricsBlobStore(admitted, metrics, Map("tenant_scope" -> scopeTag)): BlobStore
                       ).mapError(TenantRoutingError.PolicyUnavailable.apply)
                     }
      _           <- ZIO.logInfo(
                       s"Multi-tenant data plane enabled for cell ${tenantConfig.cellId.value} " +
                         s"with a bounded ${tenantConfig.maximumCachedTenants}-tenant cache"
                     )
    yield TenantDataPlane(provider, context)

  /**
   * Build clients once per process, then derive an opaque prefix for each
   * storage domain. Replicated tenant writes use a full target quorum, so a
   * successful manifest never references a block accepted with a missing
   * replica. Validating reads repair damaged copies they encounter.
   */
  private def tenantBlockStoreFactory(
    config: GravitonConfig,
    metrics: MetricsRegistry,
  ): ZIO[Scope, Throwable, TenantBlockStoreFactory] =
    if !config.replication.enabled then
      for
        base   <- ZIO
                    .fromEither(S3Config.fromEnvironment(config.s3.blockBucket, config.s3.blockPrefix))
                    .mapError(new IllegalArgumentException(_))
        client <- ZIO.acquireRelease(S3ClientLayer.make(base, metrics))(current => ZIO.attempt(current.close()).orDie)
      yield new TenantBlockStoreFactory:
        override def make(domain: StorageDomainId): Task[BlockStore] =
          val scoped = base.copy(prefix = domainPrefix(base.prefix, domain))
          ZIO.succeed(new S3BlockStore(client, S3BlockStoreConfig(scoped)): BlockStore)
    else
      for targets <- ZIO.foreach(config.replication.targets) { target =>
                       for
                         base   <- ZIO
                                     .fromEither(
                                       S3Config.fromNamedTargetEnvironment(
                                         target.name.value,
                                         target.location.value,
                                         config.s3.blockPrefix,
                                       )
                                     )
                                     .mapError(new IllegalArgumentException(_))
                         client <- ZIO.acquireRelease(S3ClientLayer.make(base, metrics))(current => ZIO.attempt(current.close()).orDie)
                       yield TenantReplicaTarget(target.name.value, target.failureDomain.value, base, client)
                     }
      yield new TenantBlockStoreFactory:
        override def make(domain: StorageDomainId): Task[BlockStore] =
          val replicas = targets.map { target =>
            val scoped                      = target.baseConfig.copy(prefix = domainPrefix(target.baseConfig.prefix, domain))
            val store: RepairableBlockStore = new S3BlockStore(target.client, S3BlockStoreConfig(scoped))
            ReplicatedBlockStore.Replica(target.name, target.failureDomain, store)
          }
          ZIO
            .fromEither(
              ReplicatedBlockStore.make(
                replicas,
                config.replication.effectiveDesiredReplicas,
                config.replication.effectiveWriteQuorum,
                ReplicaPlacement.rendezvous,
                metrics,
                config.replication.localFailureDomain.map(_.value),
              )
            )
            .mapError(new IllegalArgumentException(_))
            .map(store => store: BlockStore)

  private def buildShardcakeNode(
    config: ShardcakeUploadConfig,
    tenantDataPlane: Option[TenantDataPlane],
    blobStore: BlobStore,
    metrics: MetricsRegistry,
  ): ZIO[Scope, Throwable, Option[ShardcakeNode]] =
    if !config.enabled then ZIO.none
    else
      val layer = tenantDataPlane match
        case None         =>
          (ZLayer.succeed(blobStore) ++ ZLayer.succeed(config) ++ ZLayer.succeed(metrics)) >>> ShardcakeNode.live
        case Some(tenant) =>
          (ZLayer.succeed(tenant.provider) ++ ZLayer.succeed(config) ++ ZLayer.succeed(metrics)) >>> ShardcakeNode.liveTenants
      layer.build.map(environment => Some(environment.get[ShardcakeNode]))

  private def domainPrefix(base: String, domain: StorageDomainId): String =
    val prefix = base.stripSuffix("/")
    val suffix = s"domains/${domainToken(domain)}"
    if prefix.isEmpty then suffix else s"$prefix/$suffix"

  private[server] def tenantCellMaintenanceNamespace(
    base: RepositoryNamespace,
    cell: TenantCellId,
  ): RepositoryNamespace =
    RepositoryNamespace.applyUnsafe(s"${base.value}:tenant-cell:${cell.value}")

  private def domainToken(domain: StorageDomainId): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(domain.value.getBytes(StandardCharsets.UTF_8))
    HexFormat.of().formatHex(digest)

  private[server] def blobLayer(
    cfg: GravitonConfig,
    maintenance: MaintenanceConfig,
    blockPersistence: BlockPersistenceConfig,
    transferBudget: TransferBudget,
    dataSource: Option[DataSource],
    manifestIntegrity: Option[ManifestIntegrity] = None,
  ): ZLayer[MetricsRegistry, Throwable, BlobStore] =
    val storageLayer =
      cfg.blobBackend.toLowerCase match
        case "s3" | "minio" =>
          val manifestLayer = ZLayer.fromZIO(
            requiredDataSource(dataSource).map(ds =>
              manifestIntegrity.fold[BlobManifestRepo](new PgBlobManifestRepo(ds))(integrity =>
                PgBlobManifestRepo.authenticated(ds, integrity)
              )
            )
          )
          val metadata      = ZLayer.make[BlobManifestRepo & MaintenanceCoordinator & RepairJournal](
            manifestLayer,
            ZLayer.fromZIO(requiredDataSource(dataSource)),
            PgMaintenanceCoordinator.layer(maintenance),
            PgRepairJournal.layer,
          )
          (ZLayer.service[MetricsRegistry] ++ metadata) >+> s3BlockLayer(cfg)
        case "fs"           =>
          val root     = Path.of(cfg.fs.root)
          val metadata = ZLayer.make[BlobManifestRepo & MaintenanceCoordinator & RepairJournal](
            ZLayer.succeed[BlobManifestRepo](
              manifestIntegrity.fold[BlobManifestRepo](new FsBlobManifestRepo(root))(integrity =>
                FsBlobManifestRepo.authenticated(root, integrity)
              )
            ),
            FileMaintenanceCoordinator.layer(root, maintenance),
            ZLayer.succeed[RepairJournal](new FsRepairJournal(root)),
          )
          (ZLayer.service[MetricsRegistry] ++ metadata) >+> fsBlockLayer(cfg)
        case other          =>
          ZLayer.fail(
            new IllegalArgumentException(
              s"Unsupported GRAVITON_BLOB_BACKEND='$other' (expected 's3', 'minio', or 'fs')"
            )
          )

    val scopedBudget = transferBudget.bind(TransferScope.backend(configuredTransferBackend(cfg)))
    val casLayer     =
      (storageLayer ++ ZLayer.service[MetricsRegistry] ++ ZLayer.succeed(blockPersistence) ++ ZLayer.succeed(scopedBudget)) >>>
        CasBlobStore.coordinatedLayerWithMetricsAndPersistence

    (casLayer ++ ZLayer.service[MetricsRegistry]) >>> MetricsBlobStore.layer

  private def configuredTransferBackend(cfg: GravitonConfig): StoreBackend =
    cfg.blobBackend.toLowerCase match
      case "s3" | "minio" => StoreBackend.S3
      case "fs"           => StoreBackend.Filesystem
      case _              => StoreBackend.Runtime

  private[server] def fsBlockLayer(
    cfg: GravitonConfig
  ): ZLayer[BlobManifestRepo & RepairJournal & MetricsRegistry, Throwable, BlockStore] =
    if !cfg.replication.enabled then ZLayer.succeed[BlockStore](new FsBlockStore(Path.of(cfg.fs.root), cfg.fs.blockPrefix))
    else if cfg.replication.mode == ReplicaStorageMode.Erasure21 then
      ZLayer.fail(new IllegalArgumentException("erasure-2-1 currently requires the S3-compatible backend (AWS S3, MinIO, or Ceph RGW)"))
    else
      ZLayer.scoped {
        for
          manifests <- ZIO.service[BlobManifestRepo]
          journal   <- ZIO.service[RepairJournal]
          metrics   <- ZIO.service[MetricsRegistry]
          replicas  <- ZIO.foreach(cfg.replication.targets) { target =>
                         ZIO.attempt {
                           val store: RepairableBlockStore = new FsBlockStore(Path.of(target.location.value), cfg.fs.blockPrefix)
                           ReplicatedBlockStore.Replica(target.name.value, target.failureDomain.value, store)
                         }
                       }
          store     <- ZIO
                         .fromEither(
                           ReplicatedBlockStore.make(
                             replicas,
                             cfg.replication.effectiveDesiredReplicas,
                             cfg.replication.effectiveWriteQuorum,
                             ReplicaPlacement.rendezvous,
                             metrics,
                             cfg.replication.localFailureDomain.map(_.value),
                           )
                         )
                         .mapError(new IllegalArgumentException(_))
          repair    <- ReplicaRepairService.make(store, manifests, cfg.replication, journal, metrics)
          _         <- repair.start
        yield store: BlockStore
      }

  private[server] def s3BlockLayer(
    cfg: GravitonConfig
  ): ZLayer[BlobManifestRepo & RepairJournal & MetricsRegistry, Throwable, BlockStore] =
    if !cfg.replication.enabled then
      ZLayer.scoped {
        for
          metrics    <- ZIO.service[MetricsRegistry]
          storageCfg <- ZIO
                          .fromEither(S3Config.fromEnvironment(cfg.s3.blockBucket, cfg.s3.blockPrefix))
                          .mapError(new IllegalArgumentException(_))
          client     <- ZIO.acquireRelease(S3ClientLayer.make(storageCfg, metrics))(current => ZIO.attempt(current.close()).orDie)
        yield new S3BlockStore(client, S3BlockStoreConfig(storageCfg)): BlockStore
      }
    else
      ZLayer.scoped {
        for
          manifests <- ZIO.service[BlobManifestRepo]
          journal   <- ZIO.service[RepairJournal]
          metrics   <- ZIO.service[MetricsRegistry]
          targets   <- ZIO.foreach(cfg.replication.targets) { target =>
                         for
                           storageCfg <- ZIO
                                           .fromEither(
                                             S3Config.fromNamedTargetEnvironment(
                                               target.name.value,
                                               target.location.value,
                                               cfg.s3.blockPrefix,
                                             )
                                           )
                                           .mapError(new IllegalArgumentException(_))
                           client     <-
                             ZIO.acquireRelease(S3ClientLayer.make(storageCfg, metrics))(current => ZIO.attempt(current.close()).orDie)
                         yield (target, storageCfg, client)
                       }
          store     <- cfg.replication.mode match
                         case ReplicaStorageMode.Replicated =>
                           val replicas = targets.map { case (target, storageCfg, client) =>
                             val backend: RepairableBlockStore = new S3BlockStore(client, S3BlockStoreConfig(storageCfg))
                             ReplicatedBlockStore.Replica(target.name.value, target.failureDomain.value, backend)
                           }
                           ZIO
                             .fromEither(
                               ReplicatedBlockStore.make(
                                 replicas,
                                 cfg.replication.effectiveDesiredReplicas,
                                 cfg.replication.effectiveWriteQuorum,
                                 ReplicaPlacement.rendezvous,
                                 metrics,
                                 cfg.replication.localFailureDomain.map(_.value),
                               )
                             )
                             .mapError(new IllegalArgumentException(_))
                         case ReplicaStorageMode.Erasure21  =>
                           val fragments = targets.map { case (target, storageCfg, client) =>
                             new S3ErasureFragmentStore(target.name.value, target.failureDomain.value, client, storageCfg)
                           }
                           ZIO
                             .fromEither(
                               ErasureBlockStore.make(fragments, metrics, cfg.replication.localFailureDomain.map(_.value))
                             )
                             .mapError(new IllegalArgumentException(_))
          repair    <- ReplicaRepairService.make(store, manifests, cfg.replication, journal, metrics)
          _         <- repair.start
        yield store: BlockStore
      }

  private[server] def catalogLayer(cfg: GravitonConfig, dataSource: Option[DataSource]): ZLayer[Any, Throwable, Catalog] =
    cfg.blobBackend.toLowerCase match
      case "s3" | "minio" => ZLayer.fromZIO(requiredDataSource(dataSource)) >>> PgCatalog.layer
      case "fs"           => FsCatalog.layer(Path.of(cfg.fs.root))
      case other          =>
        ZLayer.fail(
          new IllegalArgumentException(
            s"Unsupported GRAVITON_BLOB_BACKEND='$other' (expected 's3', 'minio', or 'fs')"
          )
        )

  private[server] def resumableUploadLayer(
    cfg: GravitonConfig,
    transferBudget: TransferBudget,
    dataSource: Option[DataSource],
  ): ZLayer[MetricsRegistry, Throwable, ResumableUploadService] =
    ZLayer.scoped {
      for
        metrics <- ZIO.service[MetricsRegistry]
        service <- cfg.blobBackend.toLowerCase match
                     case "fs"           =>
                       val root    = Path.of(cfg.fs.root)
                       val target  = UploadStagingTarget
                         .from("file", "graviton-staging")
                         .fold(message => throw new IllegalStateException(message), identity)
                       val ledger  = new FsResumableUploadRepository(root)
                       val staging = new FsMutableObjectStore(root)
                       ZIO.succeed(new ResumableUploadService(ledger, staging, target, cfg.resumableUploads, metrics))
                     case "s3" | "minio" =>
                       for
                         dataSource <- requiredDataSource(dataSource)
                         storageCfg <- ZIO
                                         .fromEither(S3Config.fromEnvironment(cfg.s3.tmpBucket, "resumable"))
                                         .mapError(message => new IllegalArgumentException(message))
                         client     <-
                           ZIO.acquireRelease(S3ClientLayer.make(storageCfg, metrics))(current => ZIO.attempt(current.close()).orDie)
                         target     <- ZIO
                                         .fromEither(UploadStagingTarget.from("s3", cfg.s3.tmpBucket))
                                         .mapError(message => new IllegalArgumentException(message))
                         ledger      = new PgResumableUploadRepository(dataSource)
                         staging     = new S3MutableObjectStore(client, S3ObjectStoreConfig(storageCfg), transferBudget)
                       yield new ResumableUploadService(ledger, staging, target, cfg.resumableUploads, metrics)
                     case other          =>
                       ZIO.fail(
                         new IllegalArgumentException(
                           s"Unsupported GRAVITON_BLOB_BACKEND='$other' (expected 's3', 'minio', or 'fs')"
                         )
                       )
      yield service
    }

  /** Compatibility entrypoint for embedded tests and callers using defaults. */
  private[server] def blobLayer(cfg: GravitonConfig): ZLayer[MetricsRegistry, Throwable, BlobStore] =
    ZLayer.fromZIO(TransferBudget.make(TransferMemoryConfig.Default)).flatMap { budget =>
      blobLayer(cfg, MaintenanceConfig.Default, BlockPersistenceConfig.default, budget.get[TransferBudget], None, None)
    }

  private def validateSecurityOrFail(sec: SecurityConfig): Task[Unit] =
    ZIO
      .fromEither(sec.validate)
      .mapError(msg => new IllegalStateException(s"invalid GRAVITON_SECURITY_* config: $msg"))
      .unit

  private def capabilityLayer(sec: SecurityConfig, dataSource: Option[DataSource]): ZLayer[Any, Throwable, CapabilityCheck] =
    sec.authorizationBackend match
      case "token" => ZLayer.succeed(CapabilityCheck.tokenOnly)
      case "jdbc"  => ZLayer.fromZIO(requiredDataSource(dataSource)) >>> CapabilityCheck.jdbc
      case other   => ZLayer.fail(new IllegalArgumentException(s"Unsupported authorization backend: $other"))

  private def requiresPrimaryPostgres(
    config: GravitonConfig,
    tenantConfig: TenantDataPlaneConfig,
    security: SecurityConfig,
  ): Boolean =
    Set("s3", "minio").contains(config.blobBackend.toLowerCase) ||
      tenantConfig.enabled ||
      security.auditBackend == "jdbc" ||
      security.authorizationBackend == "jdbc"

  private def requiredDataSource(dataSource: Option[DataSource]): Task[DataSource] =
    ZIO.fromOption(dataSource).orElseFail(new IllegalStateException("PostgreSQL is required by the selected server configuration"))

  private def logSecurityPosture(sec: SecurityConfig): UIO[Unit] =
    if sec.enabled then
      val mode = sec.devSharedSecret match
        case Some(_) => "HS256 dev shared-secret"
        case None    => "OIDC RS256 with remote JWKS rotation"
      ZIO.logInfo(
        s"Security: ENABLED | mode=$mode audit=${sec.auditBackend} " +
          s"issuer=${sec.oidcIssuer.getOrElse("?")} audience=${sec.oidcAudience.getOrElse("?")} " +
          s"tls=${sec.requireTls} rate=${sec.rateLimitPerPrincipalPerSec}/s"
      )
    else
      ZIO.logWarning(
        s"Security: DISABLED (GRAVITON_SECURITY_ENABLED=false, audit=${sec.auditBackend}). " +
          "API is open; do not expose this listener to untrusted networks."
      )

  /**
   * Build the active JWT verifier.
   *
   * - Security disabled: no verifier, middleware is not applied.
   * - Security enabled + dev shared secret: HS256 verifier and `/dev/token`
   *   mint endpoint are available so local clients can curl the API.
   * - Security enabled + no dev secret: RS256 OIDC verification is built
   *   from the configured HTTPS JWKS URI.
   */
  private def buildVerifier(sec: SecurityConfig): Task[Option[JwtVerifier]] =
    if !sec.enabled then ZIO.none
    else
      sec.devSharedSecret match
        case Some(secret) =>
          ZIO.some(HmacJwtVerifier.make(secret, sec.clockSkewSeconds, sec.oidcIssuer, sec.oidcAudience))
        case None         =>
          OidcJwtVerifier.make(sec).map(Some(_))
