package graviton.runtime.config

import graviton.core.RefinedTypeExt
import io.github.iltotore.iron.constraint.numeric.{GreaterEqual, LessEqual}
import zio.{Chunk, Config, Duration, ZIO, ZLayer}

type BackendTransferConcurrency = BackendTransferConcurrency.T
object BackendTransferConcurrency extends RefinedTypeExt[Int, GreaterEqual[1] & LessEqual[65535]]:
  val Default: BackendTransferConcurrency = applyUnsafe(64)

type TenantTransferConcurrency = TenantTransferConcurrency.T
object TenantTransferConcurrency extends RefinedTypeExt[Int, GreaterEqual[1] & LessEqual[65535]]:
  val Default: TenantTransferConcurrency = applyUnsafe(16)

/** Hierarchical limits applied below the process-wide transfer ceiling. */
final case class TransferAdmissionConfig(
  maximumTenantBufferedBytes: TransferMemoryLimit = TransferMemoryLimit.applyUnsafe(128L * 1024L * 1024L),
  maximumConcurrentTenantTransfers: TenantTransferConcurrency = TenantTransferConcurrency.Default,
  maximumConcurrentBackendTransfers: BackendTransferConcurrency = BackendTransferConcurrency.Default,
  maximumResidentTenants: Int = 10000,
  maximumResidentBackends: Int = 64,
  acquisitionTimeout: Duration = Duration.fromSeconds(30),
):
  def validate: Either[String, TransferAdmissionConfig] =
    for
      _ <- Either.cond(maximumResidentTenants >= 1, (), "maximum-resident-tenants must be positive")
      _ <- Either.cond(maximumResidentBackends >= 1, (), "maximum-resident-backends must be positive")
      _ <- Either.cond(acquisitionTimeout.toNanos > 0L, (), "acquisition-timeout must be positive")
    yield this

object TransferAdmissionConfig:
  val Default: TransferAdmissionConfig = TransferAdmissionConfig()

  val config: Config[TransferAdmissionConfig] =
    (Config.long("maximum-tenant-buffered-bytes").withDefault(Default.maximumTenantBufferedBytes.value) ++
      Config.int("maximum-concurrent-tenant-transfers").withDefault(Default.maximumConcurrentTenantTransfers.value) ++
      Config.int("maximum-concurrent-backend-transfers").withDefault(Default.maximumConcurrentBackendTransfers.value) ++
      Config.int("maximum-resident-tenants").withDefault(Default.maximumResidentTenants) ++
      Config.int("maximum-resident-backends").withDefault(Default.maximumResidentBackends) ++
      Config.duration("acquisition-timeout").withDefault(Default.acquisitionTimeout))
      .mapOrFail { case (tenantBytes, tenantConcurrency, backendConcurrency, tenants, backends, timeout) =>
        (for
          refinedBytes       <- TransferMemoryLimit.either(tenantBytes)
          refinedTenant      <- TenantTransferConcurrency.either(tenantConcurrency)
          refinedConcurrency <- BackendTransferConcurrency.either(backendConcurrency)
          result             <- TransferAdmissionConfig(refinedBytes, refinedTenant, refinedConcurrency, tenants, backends, timeout).validate
        yield result).left.map(message => Config.Error.InvalidData(Chunk.empty, message))
      }
      .nested("transfer-admission")
      .nested("graviton")

  val layer: ZLayer[Any, Config.Error, TransferAdmissionConfig] = ZLayer.fromZIO(ZIO.config(config))
