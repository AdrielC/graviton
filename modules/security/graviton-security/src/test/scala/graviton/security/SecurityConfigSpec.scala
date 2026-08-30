package graviton.security

import zio.*
import zio.test.*

object SecurityConfigSpec extends ZIOSpecDefault:
  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("SecurityConfig")(
      test("rejects an out-of-range request ceiling as a typed config error") {
        val provider = ConfigProvider.fromMap(
          Map("graviton.security.max-request-bytes" -> "1099511627777")
        )

        ZIO.withConfigProvider(provider)(ZIO.config(SecurityConfig.config)).exit.map { result =>
          result match
            case Exit.Failure(cause) => assertTrue(cause.failureOption.exists(_.isInstanceOf[Config.Error]))
            case Exit.Success(_)     => assertTrue(false)
        }
      }
    )
