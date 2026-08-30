package graviton.runtime.config

import zio.{Config, ZIO, ZLayer}

/**
 * Process policy for cross-tenant physical block sharing.
 *
 * The default is fail-closed. A route marked as shared is rejected unless the
 * embedding application explicitly enables this policy after evaluating the
 * information-disclosure and residency implications.
 */
final case class TenantStorageConfig(
  allowSharedDeduplication: Boolean = false
)

object TenantStorageConfig:
  val Default: TenantStorageConfig = TenantStorageConfig()

  val config: Config[TenantStorageConfig] =
    Config
      .boolean("allow-shared-deduplication")
      .withDefault(Default.allowSharedDeduplication)
      .map(TenantStorageConfig(_))
      .nested("tenant-storage")
      .nested("graviton")

  val layer: ZLayer[Any, Config.Error, TenantStorageConfig] =
    ZLayer.fromZIO(ZIO.config(config))
