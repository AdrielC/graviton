package graviton.blob

import zio.test.*
import zio.json.*

object StorePolicySpec extends ZIOSpecDefault:
  def spec =
    suite("StorePolicy")(
      test("rejects non-positive multipart thresholds") {
        assertTrue(
          scala.util.Try(StorePolicy(BlobLayout.FramedManifestChunks, minPartSize = 0)).isFailure,
          scala.util.Try(StorePolicy(BlobLayout.FramedManifestChunks, minPartSize = -1)).isFailure,
        )
      },
      test("PolicyResolver.Static respects overrides and fallback") {
        val resolver = PolicyResolver.Static(
          overrides = Map("s3" -> StorePolicy(BlobLayout.MonolithicObject, minPartSize = 8 * 1024 * 1024)),
          fallback = StorePolicy(BlobLayout.FramedManifestChunks),
        )
        val s3Policy = resolver.policyFor("s3")
        val fsPolicy = resolver.policyFor("file")
        assertTrue(
          s3Policy.layout == BlobLayout.MonolithicObject,
          s3Policy.minPartSize == 8 * 1024 * 1024,
          fsPolicy.layout == BlobLayout.FramedManifestChunks,
        )
      },
      test("PolicyResolver.default provides sensible defaults") {
        val resolver = PolicyResolver.default
        assertTrue(
          resolver.policyFor("file").layout == BlobLayout.FramedManifestChunks,
          resolver.policyFor("s3").minPartSize == StorePolicy.DefaultMinPartSize,
          resolver.policyFor("gcs").layout == BlobLayout.FramedManifestChunks,
        )
      },
      test("StorePolicy JSON codec round-trips") {
        val policy  = StorePolicy(BlobLayout.MonolithicObject, minPartSize = 10)
        val json    = policy.toJson
        val decoded = json.fromJson[StorePolicy]
        assertTrue(decoded == Right(policy))
      },
      test("BlobLayout JSON codec round-trips values") {
        val encoded = BlobLayout.FramedManifestChunks.toJson
        val decoded = encoded.fromJson[BlobLayout]
        assertTrue(decoded == Right(BlobLayout.FramedManifestChunks))
      },
    )
