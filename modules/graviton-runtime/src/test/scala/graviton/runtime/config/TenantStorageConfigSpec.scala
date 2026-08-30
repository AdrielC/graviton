package graviton.runtime.config

import zio.*
import zio.test.*

object TenantStorageConfigSpec extends ZIOSpecDefault:
  override def spec = suite("TenantStorageConfig")(
    test("defaults cross-tenant block sharing off") {
      val provider = ConfigProvider.fromMap(Map.empty)
      ZIO.withConfigProvider(provider)(ZIO.config(TenantStorageConfig.config)).map { config =>
        assertTrue(!config.allowSharedDeduplication)
      }
    },
    test("requires an explicit ZIO Config opt-in") {
      val provider = ConfigProvider.fromMap(
        Map("graviton.tenant-storage.allow-shared-deduplication" -> "true")
      )
      ZIO.withConfigProvider(provider)(ZIO.config(TenantStorageConfig.config)).map { config =>
        assertTrue(config.allowSharedDeduplication)
      }
    },
  )
