package graviton.core.macros

import graviton.core.locator.BlobLocator
import graviton.core.types.*
import graviton.core.macros.Interpolators.*
import zio.test.*

object InterpolatorSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("Interpolator macros")(
      test("hex interpolator emits lowercase literal") {
        val value: HexLower = hex"DEADBEEF"
        assertTrue(value == "deadbeef")
      },
      test("hex interpolator rejects invalid literals") {
        val errors = scala.compiletime.testing.typeCheckErrors("import graviton.core.macros.Interpolators.*\nval x = hex\"zz\"")
        assertTrue(errors.nonEmpty) && assertTrue(errors.head.message.contains("hex literal"))
      },
      test("locator interpolator constructs BlobLocator") {
        val locator = locator"s3://my-bucket/path/to/object"
        assertTrue(locator == BlobLocator("s3", "my-bucket", "path/to/object"))
      },
      test("locator interpolator rejects malformed uri") {
        val errors =
          scala.compiletime.testing.typeCheckErrors("import graviton.core.macros.Interpolators.*\nval loc = locator\"S3://bucket\"")
        assertTrue(errors.nonEmpty) && assertTrue(errors.head.message.contains("locator literal"))
      },
    )
