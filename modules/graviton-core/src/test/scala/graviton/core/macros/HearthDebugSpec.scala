package graviton.core.macros

import zio.test.*

object HearthDebugSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("Hearth macros")(
      test("typeName emits compile-time type rendering") {
        val name = HearthDebug.typeName[Option[Int]]
        assertTrue(name.contains("Option")) && assertTrue(name.contains("Int"))
      },
    )

