package graviton.runtime.config

import zio.*

/**
 * Lightweight typed configuration from environment variables.
 *
 * Centralizes env var access so config reads are testable (via ZLayer)
 * and discoverable (all known env var names in one place).
 */
object EnvConfig:

  def string(name: String): ZIO[Any, IllegalArgumentException, String] =
    ZIO
      .attempt(sys.env.get(name).map(_.trim).filter(_.nonEmpty))
      .mapError(err => new IllegalArgumentException(s"Error reading env var '$name': ${err.getMessage}"))
      .flatMap {
        case Some(value) => ZIO.succeed(value)
        case None        => ZIO.fail(new IllegalArgumentException(s"Missing required env var '$name'"))
      }

  def stringOr(name: String, default: String): UIO[String] =
    ZIO.succeed(sys.env.get(name).map(_.trim).filter(_.nonEmpty).getOrElse(default))

  def intOr(name: String, default: Int): UIO[Int] =
    ZIO.succeed(sys.env.get(name).flatMap(_.trim.toIntOption).getOrElse(default))

  def boolean(name: String, default: Boolean = false): UIO[Boolean] =
    ZIO.succeed(sys.env.get(name).exists(v => v == "1" || v.equalsIgnoreCase("true")))

  /**
   * Known environment variable names used across Graviton modules.
   * Serves as documentation and a single source of truth.
   */
  object Keys:
    val HttpPort          = "GRAVITON_HTTP_PORT"
    val BlobBackend       = "GRAVITON_BLOB_BACKEND"
    val FsRoot            = "GRAVITON_FS_ROOT"
    val FsBlockPrefix     = "GRAVITON_FS_BLOCK_PREFIX"
    val DataDir           = "GRAVITON_DATA_DIR"
    val ChunkSize         = "GRAVITON_CHUNK_SIZE"
    val S3BlockBucket     = "GRAVITON_S3_BLOCK_BUCKET"
    val S3BlockPrefix     = "GRAVITON_S3_BLOCK_PREFIX"
    val S3Region          = "GRAVITON_S3_REGION"
    val PgJdbcUrl         = "PG_JDBC_URL"
    val PgUsername        = "PG_USERNAME"
    val PgPassword        = "PG_PASSWORD"
    val MinioUrl          = "QUASAR_MINIO_URL"
    val MinioRootUser     = "MINIO_ROOT_USER"
    val MinioRootPassword = "MINIO_ROOT_PASSWORD"
    val TestContainers    = "TESTCONTAINERS"
    val IntegrationTest   = "GRAVITON_IT"
    val MinioIT           = "GRAVITON_MINIO_IT"
