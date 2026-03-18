package graviton.runtime.config

import zio.*
import zio.test.*

object GravitonConfigSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("GravitonConfig")(
      test("default layer produces sensible defaults") {
        ZIO
          .service[GravitonConfig]
          .map { cfg =>
            assertTrue(
              cfg.httpPort == 8081,
              cfg.blobBackend == "s3",
              cfg.dataDir == ".graviton",
              cfg.chunkSize == 1048576,
              cfg.fs.blockPrefix == "cas/blocks",
              cfg.s3.blockBucket == "graviton-blocks",
              cfg.pg.jdbcUrl.isEmpty,
            )
          }
          .provide(GravitonConfig.default)
      },
      test("default FsConfig has expected paths") {
        val fs = GravitonConfig.FsConfig()
        assertTrue(
          fs.root == ".graviton",
          fs.blockPrefix == "cas/blocks",
        )
      },
      test("default S3EnvConfig has expected bucket names") {
        val s3 = GravitonConfig.S3EnvConfig()
        assertTrue(
          s3.blockBucket == "graviton-blocks",
          s3.bucket == "graviton-blobs",
          s3.tmpBucket == "graviton-tmp",
          s3.region == "us-east-1",
        )
      },
      test("default PgConfig has no JDBC URL") {
        val pg = GravitonConfig.PgConfig()
        assertTrue(pg.jdbcUrl.isEmpty, pg.username.isEmpty, pg.password.isEmpty)
      },
      test("GravitonConfig constructor respects overridden values") {
        val cfg = GravitonConfig(
          httpPort = 9090,
          blobBackend = "fs",
          dataDir = "/data",
          chunkSize = 512 * 1024,
          fs = GravitonConfig.FsConfig(root = "/data", blockPrefix = "blocks"),
          s3 = GravitonConfig.S3EnvConfig(blockBucket = "my-blocks"),
          pg = GravitonConfig.PgConfig(jdbcUrl = Some("jdbc:postgresql://localhost/db")),
        )
        assertTrue(
          cfg.httpPort == 9090,
          cfg.blobBackend == "fs",
          cfg.chunkSize == 512 * 1024,
          cfg.fs.root == "/data",
          cfg.s3.blockBucket == "my-blocks",
          cfg.pg.jdbcUrl.contains("jdbc:postgresql://localhost/db"),
        )
      },
    )
