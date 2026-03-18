package graviton.core.attributes

import graviton.core.types.*
import zio.*
import zio.test.*

object BinaryAttributesSpec extends ZIOSpecDefault:

  private val DigestLengths: List[(Algo, Int)] =
    List(
      Algo.applyUnsafe("sha-256") -> 64,
      Algo.applyUnsafe("sha-1")   -> 40,
      Algo.applyUnsafe("md5")     -> 32,
      Algo.applyUnsafe("blake3")  -> 64,
    )

  private def hexOf(length: Int): HexLower =
    HexLower.applyUnsafe("a" * length)

  def spec: Spec[TestEnvironment, Any] =
    suite("BinaryAttributes.validate")(
      test("accepts empty attributes") {
        assertTrue(BinaryAttributes.empty.validate.isRight)
      },
      test("accepts valid digest lengths for all supported algorithms") {
        check(Gen.fromIterable(DigestLengths)) { case (algo, digestLength) =>
          val attrs =
            BinaryAttributes.empty
              .advertiseDigest(algo, hexOf(digestLength))
              .confirmDigest(algo, hexOf(digestLength))
          assertTrue(attrs.validate.isRight)
        }
      },
      test("rejects invalid advertised digest lengths") {
        val attrs =
          BinaryAttributes.empty
            .advertiseDigest(Algo.applyUnsafe("sha-256"), hexOf(40))

        assertTrue(
          attrs.validate.left.exists(_.contains("advertised digest sha-256 invalid")),
          attrs.validate.left.exists(_.contains("sha-256 requires 64 hex chars")),
        )
      },
      test("rejects invalid confirmed digest lengths") {
        val attrs =
          BinaryAttributes.empty
            .confirmDigest(Algo.applyUnsafe("md5"), hexOf(31))

        assertTrue(
          attrs.validate.left.exists(_.contains("confirmed digest md5 invalid")),
          attrs.validate.left.exists(_.contains("md5 requires 32 hex chars")),
        )
      },
      test("reports all invalid digest entries") {
        val attrs =
          BinaryAttributes.empty
            .advertiseDigest(Algo.applyUnsafe("sha-256"), hexOf(40))
            .confirmDigest(Algo.applyUnsafe("md5"), hexOf(31))

        assertTrue(
          attrs.validate.left.exists(_.contains("advertised digest sha-256 invalid")),
          attrs.validate.left.exists(_.contains("confirmed digest md5 invalid")),
        )
      },
    )
