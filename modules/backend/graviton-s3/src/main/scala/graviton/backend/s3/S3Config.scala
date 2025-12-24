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
  def fromMinioEnv(
    bucketEnv: String = "GRAVITON_S3_BUCKET",
    urlEnv: String = "QUASAR_MINIO_URL",
    accessKeyEnv: String = "MINIO_ROOT_USER",
    secretKeyEnv: String = "MINIO_ROOT_PASSWORD",
    regionEnv: String = "GRAVITON_S3_REGION",
    prefixEnv: String = "GRAVITON_S3_PREFIX",
  ): Either[String, S3Config] =
    def get(name: String): Either[String, String] =
      sys.env.get(name).toRight(s"Missing env var '$name'")

    for
      bucket <- get(bucketEnv)
      url    <- get(urlEnv)
      ak     <- get(accessKeyEnv)
      sk     <- get(secretKeyEnv)
      region  = sys.env.get(regionEnv).filter(_.nonEmpty).map(Region.of).getOrElse(Region.US_EAST_1)
      prefix  = sys.env.get(prefixEnv).getOrElse("")
      endpoint <- Either
                    .catchOnly[IllegalArgumentException](URI.create(url))
                    .left
                    .map(err => s"Invalid URI in '$urlEnv': ${err.getMessage}")
    yield S3Config(
      bucket = bucket,
      region = region,
      endpointOverride = Some(endpoint),
      accessKeyId = ak,
      secretAccessKey = sk,
      forcePathStyle = true,
      prefix = prefix,
    )

