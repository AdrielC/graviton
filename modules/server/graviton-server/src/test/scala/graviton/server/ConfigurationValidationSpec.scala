package graviton.server

import graviton.integration.shardcake.{ShardcakeInternalToken, ShardcakeRegistrationConfig, ShardcakeUploadConfig}
import graviton.runtime.config.GravitonConfig
import graviton.security.SecurityConfig
import graviton.server.console.ConsoleConfig
import zio.test.*

object ConfigurationValidationSpec extends ZIOSpecDefault:
  private val clusterEnvironment = Map(
    "GRAVITON_DEPLOYMENT_PROFILE"    -> "production-cluster",
    "GRAVITON_S3_ENDPOINT"           -> "https://objects.example.com",
    "GRAVITON_S3_ACCESS_KEY"         -> "not-rendered-access-key",
    "GRAVITON_S3_SECRET_KEY"         -> "not-rendered-secret-key",
    "PG_JDBC_URL"                    -> "jdbc:postgresql://database.example.com/graviton",
    "PG_USERNAME"                    -> "graviton",
    "PG_PASSWORD"                    -> "not-rendered-database-password",
    "GRAVITON_MAINTENANCE_NAMESPACE" -> "graviton-production",
  )

  private val clusterShardcake = ShardcakeUploadConfig.Default.copy(
    enabled = true,
    internalToken = Some(ShardcakeInternalToken.applyUnsafe("configuration-validation-token-000001")),
  )

  override def spec: Spec[TestEnvironment, Any] = suite("configuration validation")(
    test("accepts the zero-config development filesystem profile") {
      for result <- ConfigurationValidation.validate(
                      GravitonConfig(),
                      ShardcakeUploadConfig.Default,
                      ShardcakeRegistrationConfig.Default,
                      ConsoleConfig.Default,
                      SecurityConfig.Default,
                      Map.empty,
                    )
      yield assertTrue(
        result.profile == ConfigurationValidation.Profile.Development,
        result.backend == "fs",
      )
    },
    test("fails a production cluster that has no security boundary") {
      for exit <- ConfigurationValidation
                    .validate(
                      GravitonConfig(blobBackend = "s3"),
                      clusterShardcake,
                      ShardcakeRegistrationConfig.Default,
                      ConsoleConfig.Default,
                      SecurityConfig.Default,
                      clusterEnvironment,
                    )
                    .exit
      yield assertTrue(exit.isFailure)
    },
    test("accepts a strict production cluster without exposing secrets") {
      val security = SecurityConfig.Default.copy(
        enabled = true,
        oidcIssuer = Some("https://identity.example.com"),
        oidcAudience = Some("graviton"),
        oidcJwksUri = Some("https://identity.example.com/.well-known/jwks.json"),
        requireTls = true,
        auditBackend = "jdbc",
      )
      for result <- ConfigurationValidation.validate(
                      GravitonConfig(blobBackend = "s3"),
                      clusterShardcake,
                      ShardcakeRegistrationConfig.Default,
                      ConsoleConfig.Default,
                      security,
                      clusterEnvironment,
                    )
      yield assertTrue(
        result.profile == ConfigurationValidation.Profile.ProductionCluster,
        result.render.contains("profile=production-cluster"),
        !result.render.contains("not-rendered"),
      )
    },
    test("rejects unbounded chunk and invalid port configuration before startup") {
      val invalid = GravitonConfig(httpPort = 0, chunkSize = Int.MaxValue)
      for exit <- ConfigurationValidation
                    .validate(
                      invalid,
                      ShardcakeUploadConfig.Default,
                      ShardcakeRegistrationConfig.Default,
                      ConsoleConfig.Default,
                      SecurityConfig.Default,
                      Map.empty,
                    )
                    .exit
      yield assertTrue(exit.isFailure)
    },
    test("rejects an invalid Shardcake registration retry window before startup") {
      val invalidRegistration = ShardcakeRegistrationConfig(
        retryInterval = zio.Duration.fromSeconds(2),
        timeout = zio.Duration.fromSeconds(1),
      )
      for exit <- ConfigurationValidation
                    .validate(
                      GravitonConfig(),
                      ShardcakeUploadConfig.Default,
                      invalidRegistration,
                      ConsoleConfig.Default,
                      SecurityConfig.Default,
                      Map.empty,
                    )
                    .exit
      yield assertTrue(exit.isFailure)
    },
  )
