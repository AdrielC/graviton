package graviton.server

import graviton.core.types.UploadChunkSize
import graviton.backend.s3.S3Config
import graviton.integration.shardcake.{ShardcakeRegistrationConfig, ShardcakeUploadConfig}
import graviton.runtime.config.GravitonConfig
import graviton.security.SecurityConfig
import graviton.server.console.ConsoleConfig
import zio.*

import java.net.URI

object ConfigurationValidation:

  enum Profile(val value: String):
    case Development       extends Profile("development")
    case Production        extends Profile("production")
    case ProductionCluster extends Profile("production-cluster")

  object Profile:
    def parse(value: String): Either[String, Profile] =
      Profile.values
        .find(_.value == value.trim.toLowerCase)
        .toRight(
          "GRAVITON_DEPLOYMENT_PROFILE must be development, production, or production-cluster"
        )

  final case class Summary(
    profile: Profile,
    backend: String,
    securityEnabled: Boolean,
    shardcakeEnabled: Boolean,
    consoleEnabled: Boolean,
  ):
    def render: String =
      s"Graviton configuration is valid: profile=${profile.value} backend=$backend " +
        s"security=$securityEnabled shardcake=$shardcakeEnabled console=$consoleEnabled"

  final case class Invalid(message: String) extends IllegalArgumentException(message)

  def validate(
    config: GravitonConfig,
    shardcake: ShardcakeUploadConfig,
    registration: ShardcakeRegistrationConfig,
    console: ConsoleConfig,
    security: SecurityConfig,
    environment: Map[String, String] = sys.env,
  ): IO[Invalid, Summary] =
    ZIO
      .fromEither {
        for
          profile <- Profile.parse(environment.getOrElse("GRAVITON_DEPLOYMENT_PROFILE", "development"))
          backend  = config.blobBackend.trim.toLowerCase
          _       <- require(Set("fs", "s3", "minio").contains(backend), "GRAVITON_BLOB_BACKEND must be fs, s3, or minio")
          _       <- requirePort("GRAVITON_HTTP_PORT", config.httpPort)
          _       <- requirePort("GRAVITON_GRPC_PORT", config.grpcPort)
          _       <- require(config.httpPort != config.grpcPort, "GRAVITON_HTTP_PORT and GRAVITON_GRPC_PORT must be different")
          _       <- UploadChunkSize
                       .either(config.chunkSize)
                       .left
                       .map(_ => "GRAVITON_CHUNK_SIZE must satisfy the bounded upload chunk constraint")
          _       <- security.validate.left.map(message => s"invalid GRAVITON_SECURITY_* config: $message")
          _       <- require(
                       Set("memory", "jdbc").contains(security.auditBackend),
                       "GRAVITON_SECURITY_AUDIT_BACKEND must be memory or jdbc",
                     )
          _       <- shardcake.validate.left.map(message => s"invalid GRAVITON_SHARDCAKE_* config: $message")
          _       <- registration.validate.left.map(message => s"invalid GRAVITON_SHARDCAKE_* config: $message")
          _       <- require(
                       !shardcake.enabled || Set("s3", "minio").contains(backend),
                       "Shardcake upload locality requires the shared S3 plus PostgreSQL composition",
                     )
          _       <- require(
                       !(console.enabled && security.enabled),
                       "GRAVITON_CONSOLE_ENABLED requires GRAVITON_SECURITY_ENABLED=false",
                     )
          _       <- validateBackend(backend, config, environment)
          _       <-
            if security.auditBackend == "jdbc" || security.authorizationBackend == "jdbc" then validatePostgres(environment)
            else Right(())
          _       <- validateProfile(profile, backend, shardcake, console, security, environment)
        yield Summary(profile, backend, security.enabled, shardcake.enabled, console.enabled)
      }
      .mapError(Invalid.apply)

  private def validateBackend(
    backend: String,
    config: GravitonConfig,
    environment: Map[String, String],
  ): Either[String, Unit] =
    backend match
      case "fs"    =>
        require(config.fs.root.trim.nonEmpty, "GRAVITON_FS_ROOT must not be empty")
      case "s3"    =>
        for
          _ <- validateAwsOrExplicitS3(environment)
          _ <- validateReplicationTargets(config, environment)
          _ <- validatePostgres(environment)
        yield ()
      case "minio" =>
        for
          _ <- validateExplicitS3(environment)
          _ <- validateReplicationTargets(config, environment)
          _ <- validatePostgres(environment)
        yield ()
      case _       => Left("unsupported backend")

  private def validateAwsOrExplicitS3(environment: Map[String, String]): Either[String, Unit] =
    val endpoint = environment.get("GRAVITON_S3_ENDPOINT").map(_.trim).filter(_.nonEmpty)
    val access   = environment.get("GRAVITON_S3_ACCESS_KEY").map(_.trim).filter(_.nonEmpty)
    val secret   = environment.get("GRAVITON_S3_SECRET_KEY").map(_.trim).filter(_.nonEmpty)

    (endpoint, access, secret) match
      case (None, None, None)          => Right(())
      case (Some(_), Some(_), Some(_)) => validateExplicitS3(environment)
      case (None, _, _)                => Left("GRAVITON_S3_ACCESS_KEY and GRAVITON_S3_SECRET_KEY require GRAVITON_S3_ENDPOINT")
      case _                           => Left("GRAVITON_S3_ENDPOINT requires both GRAVITON_S3_ACCESS_KEY and GRAVITON_S3_SECRET_KEY")

  private def validateExplicitS3(environment: Map[String, String]): Either[String, Unit] =
    for
      endpoint <- required(environment, "GRAVITON_S3_ENDPOINT")
      _        <- validateHttpUri("GRAVITON_S3_ENDPOINT", endpoint)
      _        <- required(environment, "GRAVITON_S3_ACCESS_KEY").map(_ => ())
      _        <- required(environment, "GRAVITON_S3_SECRET_KEY").map(_ => ())
    yield ()

  private def validateReplicationTargets(config: GravitonConfig, environment: Map[String, String]): Either[String, Unit] =
    if !config.replication.enabled then Right(())
    else
      for
        endpoints <- config.replication.targets.foldLeft[Either[String, List[String]]](Right(Nil)) { (acc, target) =>
                       for
                         current  <- acc
                         _        <- S3Config
                                       .fromNamedTargetEnvironment(
                                         target.name.value,
                                         target.location.value,
                                         config.s3.blockPrefix,
                                         environment,
                                       )
                                       .left
                                       .map(message => s"replication target '${target.name.value}': $message")
                         prefix    = target.name.value.toUpperCase(java.util.Locale.ROOT).replace('-', '_')
                         name      = s"GRAVITON_REPLICATION_TARGET_${prefix}_ENDPOINT"
                         endpoint <- required(environment, name)
                         _        <- validateHttpUri(name, endpoint)
                       yield endpoint.stripSuffix("/") :: current
                     }
        _         <- require(
                       endpoints.distinct.length == endpoints.length,
                       "replication targets must use distinct endpoint URLs; separate buckets on one endpoint are not independent failure domains",
                     )
      yield ()

  private def validateProfile(
    profile: Profile,
    backend: String,
    shardcake: ShardcakeUploadConfig,
    console: ConsoleConfig,
    security: SecurityConfig,
    environment: Map[String, String],
  ): Either[String, Unit] =
    profile match
      case Profile.Development                            => Right(())
      case Profile.Production | Profile.ProductionCluster =>
        for
          _ <- require(security.enabled, "production profiles require GRAVITON_SECURITY_ENABLED=true")
          _ <- require(security.devSharedSecret.isEmpty, "production profiles reject GRAVITON_SECURITY_DEV_SHARED_SECRET")
          _ <- require(security.requireTls, "production profiles require GRAVITON_SECURITY_REQUIRE_TLS=true")
          _ <- require(!console.enabled, "production profiles reject the unauthenticated local console")
          _ <- require(security.auditBackend == "jdbc", "production profiles require GRAVITON_SECURITY_AUDIT_BACKEND=jdbc")
          _ <-
            if profile == Profile.ProductionCluster then
              for
                _ <- require(Set("s3", "minio").contains(backend), "production-cluster requires the S3 plus PostgreSQL backend")
                _ <- require(shardcake.enabled, "production-cluster requires GRAVITON_SHARDCAKE_ENABLED=true")
                _ <- required(environment, "GRAVITON_MAINTENANCE_NAMESPACE").map(_ => ())
              yield ()
            else Right(())
        yield ()

  private def required(environment: Map[String, String], name: String): Either[String, String] =
    environment.get(name).map(_.trim).filter(_.nonEmpty).toRight(s"$name is required")

  private def validatePostgres(environment: Map[String, String]): Either[String, Unit] =
    for
      jdbc <- required(environment, "PG_JDBC_URL")
      _    <- require(jdbc.startsWith("jdbc:postgresql://"), "PG_JDBC_URL must be a PostgreSQL JDBC URL")
      _    <- required(environment, "PG_USERNAME").map(_ => ())
      _    <- required(environment, "PG_PASSWORD").map(_ => ())
    yield ()

  private def requirePort(name: String, value: Int): Either[String, Unit] =
    require(value >= 1 && value <= 65535, s"$name must be within 1..65535")

  private def require(condition: Boolean, message: String): Either[String, Unit] =
    Either.cond(condition, (), message)

  private def validateHttpUri(name: String, value: String): Either[String, Unit] =
    scala.util.Try(URI.create(value)).toEither.left.map(_ => s"$name must be an absolute HTTP or HTTPS URI").flatMap { uri =>
      require(
        uri.isAbsolute && Set("http", "https").contains(Option(uri.getScheme).fold("")(_.toLowerCase)) && Option(uri.getHost)
          .exists(_.nonEmpty),
        s"$name must be an absolute HTTP or HTTPS URI",
      )
    }
