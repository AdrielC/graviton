package graviton.runtime.config

import zio.*
import zio.test.*

import java.util.Base64

object ManifestIntegrityConfigSpec extends ZIOSpecDefault:
  private val active   = Base64.getEncoder.encodeToString(Array.fill[Byte](32)(1))
  private val previous = Base64.getEncoder.encodeToString(Array.fill[Byte](32)(2))

  override def spec =
    suite("ManifestIntegrityConfig")(
      test("requires an active key when authentication is enabled") {
        val provider = ConfigProvider.fromMap(Map("graviton.manifest-integrity.required" -> "true"))
        ZIO.withConfigProvider(provider)(ZIO.config(ManifestIntegrityConfig.config)).exit.map(exit => assertTrue(exit.isFailure))
      },
      test("loads a redacted active and verification key ring") {
        val provider = ConfigProvider.fromMap(
          Map(
            "graviton.manifest-integrity.required"             -> "true",
            "graviton.manifest-integrity.key-id"               -> "active-v2",
            "graviton.manifest-integrity.hmac-key-base64"      -> active,
            "graviton.manifest-integrity.previous-keys-base64" -> s"retired-v1:$previous",
          )
        )
        for
          config    <- ZIO.withConfigProvider(provider)(ZIO.config(ManifestIntegrityConfig.config))
          integrity <- config.build
        yield assertTrue(
          integrity.nonEmpty,
          config.toString.contains("keys=<redacted:2>"),
          !config.toString.contains(active),
          !config.toString.contains(previous),
        )
      },
      test("rejects active key reuse in the previous key ring") {
        val provider = ConfigProvider.fromMap(
          Map(
            "graviton.manifest-integrity.required"             -> "true",
            "graviton.manifest-integrity.key-id"               -> "active-v2",
            "graviton.manifest-integrity.hmac-key-base64"      -> active,
            "graviton.manifest-integrity.previous-keys-base64" -> s"active-v2:$previous",
          )
        )
        ZIO.withConfigProvider(provider)(ZIO.config(ManifestIntegrityConfig.config)).exit.map(exit => assertTrue(exit.isFailure))
      },
    )
