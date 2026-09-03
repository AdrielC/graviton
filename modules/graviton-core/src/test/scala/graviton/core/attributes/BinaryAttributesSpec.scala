package graviton.core.attributes

import graviton.core.types.{Algo, Mime}
import graviton.core.macros.Interpolators.hex
import zio.blocks.mediatype.MediaType
import zio.test.*

object BinaryAttributesSpec extends ZIOSpecDefault:

  override def spec =
    suite("BinaryAttributes")(
      test("retains typed member lookup across the published boundary") {
        val attributes: BinaryAttributes                = BinaryAttributes.empty
        val validated: Either[String, BinaryAttributes] = attributes.validate

        assertTrue(validated == Right(attributes))
      },
      test("preserves a valid typed media type with quoted parameters") {
        val mediaType = MediaType("application", "example", parameters = Map("name" -> "\"a;b\""))
        val result    = for
          advertised <- BinaryAttributes.empty.advertiseMediaType(mediaType)
          confirmed  <- advertised.confirmMediaType(mediaType)
          parsed     <- confirmed.mediaType
          validated  <- confirmed.validate
        yield parsed -> validated

        assertTrue(
          result.map(_._1.map(_.parameters)) == Right(Some(Map("name" -> "\"a;b\""))),
          result.isRight,
        )
      },
      test("legacy MIME setters cannot bypass validation") {
        val invalid = Mime.applyUnsafe("application/pdf; charset")
        val hidden  = BinaryAttributes.empty
          .advertiseMime(invalid)
          .confirmMime(Mime.applyUnsafe("application/pdf"))

        assertTrue(hidden.validate.swap.exists(_.contains("advertised MIME invalid")))
      },
      test("typed media setters reject invalid programmatic values") {
        val control = MediaType("application", "pdf", parameters = Map("name" -> "\"line\nfeed\""))
        val tooLong = MediaType("application", "pdf", parameters = Map("name" -> ("x" * 256)))

        assertTrue(
          BinaryAttributes.empty.advertiseMediaType(control).isLeft,
          BinaryAttributes.empty.advertiseMediaType(tooLong).isLeft,
        )
      },
      test("retains advertised and confirmed transfer checksums") {
        val md5      = Algo("md5")
        val expected = hex"900150983cd24fb0d6963f7d28e17f72"
        val result   = BinaryAttributes.empty
          .advertiseDigest(md5, expected)
          .confirmDigest(md5, expected)

        assertTrue(
          result.advertisedDigests.get(md5).contains(expected),
          result.digest(md5).contains(expected),
          result.validate.isRight,
        )
      },
    )

end BinaryAttributesSpec
