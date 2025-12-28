package graviton.core.macros

import graviton.core.locator.BlobLocator
import graviton.core.ranges.Span
import graviton.core.macros.Interpolators.*
import zio.test.*
import graviton.core.types.HexLower
import graviton.core.types.given

object InterpolatorSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("Interpolator macros")(
      test("hex interpolator emits lowercase literal") {
        val value = hex"deadbeef"
        assertTrue(value == HexLower.either("deadbeef").toOption.get)
      },
      test("bin interpolator returns KeyBits") {
        val bits = bin"101010101"
        assertTrue(bits.size == 9L)
      },
      test("bin interpolator rejects invalid literals") {
        val errors = scala.compiletime.testing.typeCheckErrors("import graviton.core.macros.Interpolators.*\nval x = bin\"abc\"")
        assertTrue(errors.nonEmpty) &&
        assertTrue(errors.head.message.contains("bin literal"))
      },
      test("hex interpolator rejects invalid literals") {
        val errors = scala.compiletime.testing.typeCheckErrors("import graviton.core.macros.Interpolators.*\nval x = hex\"zz\"")
        assertTrue(errors.nonEmpty) &&
        assertTrue(errors.head.message.contains("hex literal"))
      },
      test("locator interpolator constructs BlobLocator") {
        val locator  = locator"s3://my-bucket/path/to/object"
        val expected = BlobLocator.from("s3", "my-bucket", "path/to/object").toOption.get
        assertTrue(locator == expected)
      },
      test("locator interpolator rejects malformed uri") {
        val errors =
          scala.compiletime.testing.typeCheckErrors("import graviton.core.macros.Interpolators.*\nval loc = locator\"S3://bucket\"")
        assertTrue(errors.nonEmpty) && assertTrue(errors.head.message.contains("locator literal"))
      },
      test("span interpolator emits inclusive spans by default") {
        val window = span"0..42"
        assertTrue(window == Span.unsafe(0L, 42L))
      },
      test("span interpolator honors exclusivity markers") {
        val halfOpen = span"[0..42)"
        assertTrue(halfOpen == Span.unsafe(0L, 41L))
      },
      test("span interpolator supports runtime arguments") {
        val start = 10L
        val size  = 5L
        val block = span"$start..${start + size - 1}"
        assertTrue(block == Span.unsafe(10L, 14L))
      },
    )
