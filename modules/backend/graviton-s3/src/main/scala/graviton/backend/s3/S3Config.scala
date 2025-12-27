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
  accessKeyId: String,
  secretAccessKey: String,
  forcePathStyle: Boolean = true,
  prefix: String = "",
)

object S3Config:
  /**
   * Build an S3-compatible config from the "MinIO-style" env contract, but with the bucket/prefix
   * provided explicitly (so callers can safely apply defaults without requiring bucket env vars).
   *
   * Required env vars:
   * - QUASAR_MINIO_URL
   * - MINIO_ROOT_USER
   * - MINIO_ROOT_PASSWORD
   *
   * Optional env vars:
   * - GRAVITON_S3_REGION (defaults to us-east-1)
   */
  def fromEndpointEnv(
    bucket: String,
    prefix: String = "",
    urlEnv: String = "QUASAR_MINIO_URL",
    accessKeyEnv: String = "MINIO_ROOT_USER",
    secretKeyEnv: String = "MINIO_ROOT_PASSWORD",
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
      accessKeyId = ak,
      secretAccessKey = sk,
      forcePathStyle = forcePathStyle,
      prefix = prefix,
    )

  def fromMinioEnv(
    bucketEnv: String = "GRAVITON_S3_BUCKET",
    urlEnv: String = "QUASAR_MINIO_URL",
    accessKeyEnv: String = "MINIO_ROOT_USER",
    secretKeyEnv: String = "MINIO_ROOT_PASSWORD",
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
