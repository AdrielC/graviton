package graviton.core.attributes

import graviton.core.types.*
import zio.test.*

object BinaryAttributesSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] =
    suite("BinaryAttributes")(
      test("accepts valid advertised and confirmed metadata") {
        val algo  = Algo.applyUnsafe("sha-256")
        val attrs =
          BinaryAttributes.empty
            .advertiseMime(Mime.applyUnsafe("text/plain; charset=utf-8"))
            .advertiseDigest(algo, HexLower.applyUnsafe("a" * 64))
            .confirmSize(FileSize.unsafe(3))
            .confirmChunkCount(ChunkCount.unsafe(2))
            .confirmDigest(algo, HexLower.applyUnsafe("b" * 64))

        assertTrue(attrs.validate == Right(attrs))
      },
      test("rejects digests whose length does not match the algorithm") {
        val attrs =
          BinaryAttributes.empty
            .advertiseDigest(
              Algo.applyUnsafe("sha-256"),
              HexLower.applyUnsafe("ab"),
            )

        val result = attrs.validate

        assertTrue(
          result.isLeft,
          result.left.exists(_.contains("advertised digest 'sha-256' invalid")),
        )
      },
      test("rejects MIME values without a media type slash") {
        val attrs =
          BinaryAttributes.empty
            .advertiseMime(Mime.applyUnsafe("application"))

        val result = attrs.validate

        assertTrue(
          result.isLeft,
          result.left.exists(_.contains("advertised mime must be a valid media type")),
        )
      },
      test("rejects chunk counts larger than the available size") {
        val attrs =
          BinaryAttributes.empty
            .advertiseSize(FileSize.unsafe(2))
            .advertiseChunkCount(ChunkCount.unsafe(3))

        val result = attrs.validate

        assertTrue(
          result.isLeft,
          result.left.exists(_.contains("advertised chunk count 3 cannot exceed size 2")),
        )
      },
    )
