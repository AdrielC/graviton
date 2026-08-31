package graviton.server

import graviton.integration.redis.RedisAdmissionConfig
import graviton.runtime.config.{TenantDataPlaneConfig, TransferAdmissionConfig, TransferMemoryConfig}
import graviton.integration.shardcake.{ShardcakeInternalToken, ShardcakeRegistrationConfig, ShardcakeUploadConfig}
import graviton.runtime.config.{GravitonConfig, ReplicaStorageMode, ReplicaTargetConfig, ReplicationConfig}
import graviton.runtime.tenant.TenantCellId
import graviton.security.SecurityConfig
import graviton.server.console.ConsoleConfig
import zio.{Config, Duration}
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
    test("accepts AWS task-role credentials without a custom S3 endpoint") {
      val security       = SecurityConfig.Default.copy(
        enabled = true,
        oidcIssuer = Some("https://identity.example.com"),
        oidcAudience = Some("graviton"),
        oidcJwksUri = Some("https://identity.example.com/.well-known/jwks.json"),
        requireTls = true,
        auditBackend = "jdbc",
      )
      val awsEnvironment = clusterEnvironment -- Set(
        "GRAVITON_S3_ENDPOINT",
        "GRAVITON_S3_ACCESS_KEY",
        "GRAVITON_S3_SECRET_KEY",
      )

      for result <- ConfigurationValidation.validate(
                      GravitonConfig(blobBackend = "s3"),
                      clusterShardcake,
                      ShardcakeRegistrationConfig.Default,
                      ConsoleConfig.Default,
                      security,
                      awsEnvironment,
                    )
      yield assertTrue(result.profile == ConfigurationValidation.Profile.ProductionCluster)
    },
    test("requires a complete explicit credential tuple for MinIO and custom S3 endpoints") {
      val postgres     = Map(
        "PG_JDBC_URL" -> "jdbc:postgresql://database.example.com/graviton",
        "PG_USERNAME" -> "graviton",
        "PG_PASSWORD" -> "not-rendered-database-password",
      )
      val minioMissing = postgres
      val danglingS3   = postgres + ("GRAVITON_S3_ACCESS_KEY" -> "dangling")

      for
        minioExit <- ConfigurationValidation
                       .validate(
                         GravitonConfig(blobBackend = "minio"),
                         ShardcakeUploadConfig.Default,
                         ShardcakeRegistrationConfig.Default,
                         ConsoleConfig.Default,
                         SecurityConfig.Default,
                         minioMissing,
                       )
                       .exit
        s3Exit    <- ConfigurationValidation
                       .validate(
                         GravitonConfig(blobBackend = "s3"),
                         ShardcakeUploadConfig.Default,
                         ShardcakeRegistrationConfig.Default,
                         ConsoleConfig.Default,
                         SecurityConfig.Default,
                         danglingS3,
                       )
                       .exit
      yield assertTrue(minioExit.isFailure, s3Exit.isFailure)
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
    test("rejects several declared failure domains that resolve to one endpoint") {
      val targets     = ReplicaTargetConfig
        .parseList("a|zone-a|blocks-a,b|zone-b|blocks-b,c|zone-c|blocks-c")
        .toOption
        .get
      val config      = GravitonConfig(
        blobBackend = "s3",
        replication = ReplicationConfig(
          targets = targets,
          desiredReplicas = Some(3),
          writeQuorum = Some(2),
          mode = ReplicaStorageMode.Erasure21,
        ),
      )
      val named       = List("A", "B", "C").flatMap { name =>
        List(
          s"GRAVITON_REPLICATION_TARGET_${name}_ENDPOINT"   -> "https://same.example.com",
          s"GRAVITON_REPLICATION_TARGET_${name}_ACCESS_KEY" -> name.toLowerCase,
          s"GRAVITON_REPLICATION_TARGET_${name}_SECRET_KEY" -> "not-rendered-target-secret",
        )
      }.toMap
      val environment = clusterEnvironment.updated("GRAVITON_DEPLOYMENT_PROFILE", "development") ++ named

      for exit <- ConfigurationValidation
                    .validate(
                      config,
                      ShardcakeUploadConfig.Default,
                      ShardcakeRegistrationConfig.Default,
                      ConsoleConfig.Default,
                      SecurityConfig.Default,
                      environment,
                    )
                    .exit
      yield assertTrue(exit.isFailure)
    },
    test("validates distributed admission timeout composition before startup") {
      val redis = RedisAdmissionConfig.Default.copy(enabled = true, acquisitionTimeout = Duration.fromSeconds(20))
      val local = TransferAdmissionConfig.Default.copy(acquisitionTimeout = Duration.fromSeconds(10))
      for exit <- Main
                    .validateDistributedAdmission(
                      redis,
                      TransferMemoryConfig.Default,
                      local,
                      TenantDataPlaneConfig.Default,
                    )
                    .exit
      yield assertTrue(exit.isFailure)
    },
    test("requires authenticated TLS Redis in multi-tenant mode") {
      val cell   = TenantCellId.applyUnsafe("production-cell")
      val tenant = TenantDataPlaneConfig.Default.copy(enabled = true, cellId = cell)
      val unsafe = RedisAdmissionConfig.Default.copy(enabled = true, cellId = cell)
      val safe   = unsafe.copy(tls = true, verifyCertificate = true, password = Some(Config.Secret("test-password")))
      for
        unsafeExit <- Main
                        .validateDistributedAdmission(
                          unsafe,
                          TransferMemoryConfig.Default,
                          TransferAdmissionConfig.Default,
                          tenant,
                        )
                        .exit
        safeExit   <- Main
                        .validateDistributedAdmission(
                          safe,
                          TransferMemoryConfig.Default,
                          TransferAdmissionConfig.Default,
                          tenant,
                        )
                        .exit
      yield assertTrue(unsafeExit.isFailure, safeExit.isSuccess)
    },
  )
