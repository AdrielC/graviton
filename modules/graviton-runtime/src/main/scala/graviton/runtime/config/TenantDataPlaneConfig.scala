package graviton.runtime.config

import graviton.runtime.tenant.TenantCellId
import zio.{Chunk, Config, Duration, ZIO, ZLayer}

/** Packaged-server controls for durable multi-tenant routing and admission. */
final case class TenantDataPlaneConfig(
  enabled: Boolean = false,
  cellId: TenantCellId = TenantCellId.Default,
  maximumCachedTenants: Int = 10000,
  policyCacheTtl: Duration = Duration.fromSeconds(30),
  admissionTimeout: Duration = Duration.fromSeconds(10),
):
  def validate: Either[String, TenantDataPlaneConfig] =
    for
      _ <- Either.cond(maximumCachedTenants >= 1, (), "maximum-cached-tenants must be positive")
      _ <- Either.cond(policyCacheTtl.toNanos > 0L, (), "policy-cache-ttl must be positive")
      _ <- Either.cond(admissionTimeout.toNanos > 0L, (), "admission-timeout must be positive")
    yield this

object TenantDataPlaneConfig:
  val Default: TenantDataPlaneConfig = TenantDataPlaneConfig()

  val config: Config[TenantDataPlaneConfig] =
    (Config.boolean("enabled").withDefault(Default.enabled) ++
      Config.string("cell-id").withDefault(Default.cellId.value) ++
      Config.int("maximum-cached-tenants").withDefault(Default.maximumCachedTenants) ++
      Config.duration("policy-cache-ttl").withDefault(Default.policyCacheTtl) ++
      Config.duration("admission-timeout").withDefault(Default.admissionTimeout))
      .mapOrFail { case (enabled, rawCellId, maximumCachedTenants, policyCacheTtl, admissionTimeout) =>
        TenantCellId
          .either(rawCellId)
          .map(cellId => TenantDataPlaneConfig(enabled, cellId, maximumCachedTenants, policyCacheTtl, admissionTimeout))
          .left
          .map(message => Config.Error.InvalidData(Chunk.empty, s"invalid multi-tenant cell-id: $message"))
      }
      .nested("multi-tenant")
      .nested("graviton")

  val layer: ZLayer[Any, Config.Error, TenantDataPlaneConfig] =
    ZLayer.fromZIO(ZIO.config(config))
