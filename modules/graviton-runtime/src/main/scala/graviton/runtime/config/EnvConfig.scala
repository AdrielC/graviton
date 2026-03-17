package graviton.runtime.config

import zio.*
import zio.Config

/**
 * Typed Graviton configuration via ZIO Config.
 *
 * Reads from environment variables with `GRAVITON_` prefix.
 * All fields have sensible defaults so the app runs without any config.
 */
final case class GravitonConfig(
  httpPort: Int = 8081,
  blobBackend: String = "s3",
  dataDir: String = ".graviton",
  chunkSize: Int = 1048576,
  fs: GravitonConfig.FsConfig = GravitonConfig.FsConfig(),
  s3: GravitonConfig.S3EnvConfig = GravitonConfig.S3EnvConfig(),
  pg: GravitonConfig.PgConfig = GravitonConfig.PgConfig(),
)

object GravitonConfig:

  final case class FsConfig(
    root: String = ".graviton",
    blockPrefix: String = "cas/blocks",
  )

  final case class S3EnvConfig(
    blockBucket: String = "graviton-blocks",
    blockPrefix: String = "cas/blocks",
    bucket: String = "graviton-blobs",
    tmpBucket: String = "graviton-tmp",
    region: String = "us-east-1",
  )

  final case class PgConfig(
    jdbcUrl: Option[String] = None,
    username: Option[String] = None,
    password: Option[String] = None,
  )

  private val fsConfig: Config[FsConfig] =
    (Config.string("root").withDefault("graviton") ++
      Config.string("block-prefix").withDefault("cas/blocks"))
      .map { case (root, prefix) =>
        FsConfig(root, prefix)
      }
      .nested("fs")

  private val s3Config: Config[S3EnvConfig] =
    (Config.string("block-bucket").withDefault("graviton-blocks") ++
      Config.string("block-prefix").withDefault("cas/blocks") ++
      Config.string("bucket").withDefault("graviton-blobs") ++
      Config.string("tmp-bucket").withDefault("graviton-tmp") ++
      Config.string("region").withDefault("us-east-1"))
      .map { case (bb, bp, b, tb, r) =>
        S3EnvConfig(bb, bp, b, tb, r)
      }
      .nested("s3")

  private val pgConfig: Config[PgConfig] =
    (Config.string("jdbc-url").optional ++
      Config.string("username").optional ++
      Config.string("password").optional)
      .map { case (url, user, pass) =>
        PgConfig(url, user, pass)
      }
      .nested("pg")

  val config: Config[GravitonConfig] =
    (Config.int("http-port").withDefault(8081) ++
      Config.string("blob-backend").withDefault("s3") ++
      Config.string("data-dir").withDefault(".graviton") ++
      Config.int("chunk-size").withDefault(1048576) ++
      fsConfig ++ s3Config ++ pgConfig)
      .map { case (port, backend, dataDir, chunk, fs, s3, pg) =>
        GravitonConfig(port, backend, dataDir, chunk, fs, s3, pg)
      }
      .nested("graviton")

  val layer: ZLayer[Any, Config.Error, GravitonConfig] =
    ZLayer.fromZIO(ZIO.config(config))

  val default: ULayer[GravitonConfig] =
    ZLayer.succeed(GravitonConfig())
