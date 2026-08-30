package graviton.backend.s3

import zio.test.*

object S3ConfigSpec extends ZIOSpecDefault:
  override def spec = suite("S3Config named replication targets")(
    test("resolves a target-specific endpoint without inheriting global credentials") {
      val environment = Map(
        "GRAVITON_S3_ENDPOINT"                          -> "http://shared:9000",
        "GRAVITON_S3_ACCESS_KEY"                        -> "shared",
        "GRAVITON_S3_SECRET_KEY"                        -> "shared-secret",
        "GRAVITON_REPLICATION_TARGET_WEST_A_ENDPOINT"   -> "https://west-a.example.com",
        "GRAVITON_REPLICATION_TARGET_WEST_A_ACCESS_KEY" -> "west-a",
        "GRAVITON_REPLICATION_TARGET_WEST_A_SECRET_KEY" -> "west-a-secret",
        "GRAVITON_REPLICATION_TARGET_WEST_A_REGION"     -> "us-west-2",
      )
      val result      = S3Config.fromNamedTargetEnvironment("west-a", "blocks-west", "cas/blocks", environment)

      assertTrue(
        result.exists(_.endpointOverride.exists(_.toString == "https://west-a.example.com")),
        result.exists(_.accessKeyId.contains("west-a")),
        result.exists(_.bucket == "blocks-west"),
        result.exists(_.region.id() == "us-west-2"),
      )
    },
    test("rejects an incomplete named credential pair") {
      val environment = Map(
        "GRAVITON_REPLICATION_TARGET_A_ENDPOINT"   -> "http://a:9000",
        "GRAVITON_REPLICATION_TARGET_A_ACCESS_KEY" -> "a",
      )
      assertTrue(S3Config.fromNamedTargetEnvironment("a", "blocks", "cas", environment).isLeft)
    },
  )
