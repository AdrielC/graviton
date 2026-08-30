package graviton.backend.s3

import software.amazon.awssdk.regions.Region

import java.net.URI

/**
 * S3-compatible configuration (works with AWS S3 and MinIO).
 *
 * For MinIO, set:
 * - endpointOverride = Some(URI("http://minio:9000"))
 * - forcePathStyle = true
 */
final case class S3Config(
  bucket: String,
  region: Region = Region.US_EAST_1,
  endpointOverride: Option[URI] = None,
  accessKeyId: Option[String] = None,
  secretAccessKey: Option[String] = None,
  forcePathStyle: Boolean = false,
  prefix: String = "",
)

object S3Config:
  private val EndpointEnv  = "GRAVITON_S3_ENDPOINT"
  private val AccessKeyEnv = "GRAVITON_S3_ACCESS_KEY"
  private val SecretKeyEnv = "GRAVITON_S3_SECRET_KEY"

  /**
   * Build an S3-compatible config from an explicit endpoint contract, but with the bucket/prefix
   * provided explicitly (so callers can safely apply defaults without requiring bucket env vars).
   *
   * Required env vars:
   * - GRAVITON_S3_ENDPOINT
   * - GRAVITON_S3_ACCESS_KEY
   * - GRAVITON_S3_SECRET_KEY
   *
   * Optional env vars:
   * - GRAVITON_S3_REGION (defaults to us-east-1)
   */
  def fromEndpointEnv(
    bucket: String,
    prefix: String = "",
    urlEnv: String = EndpointEnv,
    accessKeyEnv: String = AccessKeyEnv,
    secretKeyEnv: String = SecretKeyEnv,
    regionEnv: String = "GRAVITON_S3_REGION",
    forcePathStyle: Boolean = true,
  ): Either[String, S3Config] =
    def get(name: String): Either[String, String] =
      sys.env.get(name).map(_.trim).filter(_.nonEmpty).toRight(s"Missing env var '$name'")

    for
      url      <- get(urlEnv)
      ak       <- get(accessKeyEnv)
      sk       <- get(secretKeyEnv)
      region    = sys.env.get(regionEnv).map(_.trim).filter(_.nonEmpty).map(Region.of).getOrElse(Region.US_EAST_1)
      endpoint <- scala.util.Try(URI.create(url)).toEither.left.map(err => s"Invalid URI in '$urlEnv': ${err.getMessage}")
    yield S3Config(
      bucket = bucket.trim,
      region = region,
      endpointOverride = Some(endpoint),
      accessKeyId = Some(ak),
      secretAccessKey = Some(sk),
      forcePathStyle = forcePathStyle,
      prefix = prefix,
    )

  def fromMinioEnv(
    bucketEnv: String = "GRAVITON_S3_BUCKET",
    urlEnv: String = EndpointEnv,
    accessKeyEnv: String = AccessKeyEnv,
    secretKeyEnv: String = SecretKeyEnv,
    regionEnv: String = "GRAVITON_S3_REGION",
    prefixEnv: String = "GRAVITON_S3_PREFIX",
  ): Either[String, S3Config] =
    val bucket = sys.env.get(bucketEnv).map(_.trim).filter(_.nonEmpty).toRight(s"Missing env var '$bucketEnv'")
    val prefix = sys.env.get(prefixEnv).map(_.trim).getOrElse("")

    for
      b <- bucket
      c <- fromEndpointEnv(
             bucket = b,
             prefix = prefix,
             urlEnv = urlEnv,
             accessKeyEnv = accessKeyEnv,
             secretKeyEnv = secretKeyEnv,
             regionEnv = regionEnv,
             forcePathStyle = true,
           )
    yield c

  /** Use explicit S3-compatible settings when an endpoint exists, otherwise AWS defaults. */
  def fromEnvironment(bucket: String, prefix: String = ""): Either[String, S3Config] =
    sys.env.get(EndpointEnv).map(_.trim).filter(_.nonEmpty) match
      case Some(_) => fromEndpointEnv(bucket, prefix)
      case None    =>
        val region = sys.env.get("GRAVITON_S3_REGION").map(_.trim).filter(_.nonEmpty).map(Region.of).getOrElse(Region.US_EAST_1)
        Right(S3Config(bucket = bucket, region = region, prefix = prefix))

  /**
   * Resolve one independently addressable replication target.
   *
   * The target name `west-a` maps to the environment prefix
   * `GRAVITON_REPLICATION_TARGET_WEST_A`. Endpoint and credentials are never
   * inherited from the process-wide S3 variables, which prevents an operator
   * from accidentally declaring several buckets on one endpoint as separate
   * failure domains.
   */
  def fromNamedTargetEnvironment(
    targetName: String,
    bucket: String,
    prefix: String,
    environment: Map[String, String] = sys.env,
  ): Either[String, S3Config] =
    val normalized = targetName.trim.toUpperCase(java.util.Locale.ROOT).replace('-', '_')
    val envPrefix  = s"GRAVITON_REPLICATION_TARGET_$normalized"
    val endpoint   = s"${envPrefix}_ENDPOINT"
    val accessKey  = s"${envPrefix}_ACCESS_KEY"
    val secretKey  = s"${envPrefix}_SECRET_KEY"
    val region     = s"${envPrefix}_REGION"

    environment.get(endpoint).map(_.trim).filter(_.nonEmpty) match
      case Some(url) =>
        for
          ak       <- environment.get(accessKey).map(_.trim).filter(_.nonEmpty).toRight(s"Missing env var '$accessKey'")
          sk       <- environment.get(secretKey).map(_.trim).filter(_.nonEmpty).toRight(s"Missing env var '$secretKey'")
          endpoint <- scala.util.Try(URI.create(url)).toEither.left.map(err => s"Invalid URI in '$endpoint': ${err.getMessage}")
          resolved  = environment.get(region).map(_.trim).filter(_.nonEmpty).map(Region.of).getOrElse(Region.US_EAST_1)
        yield S3Config(bucket.trim, resolved, Some(endpoint), Some(ak), Some(sk), forcePathStyle = true, prefix)
      case None      =>
        val credentials = (environment.get(accessKey).filter(_.nonEmpty), environment.get(secretKey).filter(_.nonEmpty))
        credentials match
          case (Some(_), None) | (None, Some(_)) => Left(s"'$accessKey' and '$secretKey' must be configured together")
          case (maybeAccess, maybeSecret)        =>
            val resolvedRegion = environment.get(region).map(_.trim).filter(_.nonEmpty).map(Region.of).getOrElse(Region.US_EAST_1)
            Right(
              S3Config(
                bucket = bucket.trim,
                region = resolvedRegion,
                accessKeyId = maybeAccess,
                secretAccessKey = maybeSecret,
                prefix = prefix,
              )
            )
