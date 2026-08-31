package graviton.integration.redis

import zio.*
import zio.test.*

object RedisAdmissionConfigSpec extends ZIOSpecDefault:
  override def spec: Spec[TestEnvironment, Any] = suite("RedisAdmissionConfig")(
    test("loads safe disabled defaults") {
      ZIO.withConfigProvider(ConfigProvider.fromMap(Map.empty))(ZIO.config(RedisAdmissionConfig.config)).map { config =>
        assertTrue(
          !config.enabled,
          config.validate.isRight,
          config.renewalInterval.toMillis * 3L <= config.leaseTtl.toMillis,
        )
      }
    },
    test("rejects a renewal interval that cannot preserve the lease margin") {
      val provider = ConfigProvider.fromMap(
        Map(
          "graviton.distributed-admission.redis.lease-ttl"        -> "3s",
          "graviton.distributed-admission.redis.renewal-interval" -> "2s",
        )
      )
      ZIO.withConfigProvider(provider)(ZIO.config(RedisAdmissionConfig.config)).exit.map(exit => assertTrue(exit.isFailure))
    },
    test("keeps authentication material redacted") {
      val secret   = "redis-password-that-must-not-render"
      val provider = ConfigProvider.fromMap(
        Map(
          "graviton.distributed-admission.redis.enabled"  -> "true",
          "graviton.distributed-admission.redis.password" -> secret,
        )
      )
      ZIO.withConfigProvider(provider)(ZIO.config(RedisAdmissionConfig.config)).map { config =>
        assertTrue(
          config.password.exists(_.stringValue == secret),
          !config.toString.contains(secret),
        )
      }
    },
    test("rejects present but empty authentication fields") {
      assertTrue(
        RedisAdmissionConfig.Default.copy(username = Some("")).validate.isLeft,
        RedisAdmissionConfig.Default.copy(password = Some(Config.Secret(""))).validate.isLeft,
      )
    },
  )
