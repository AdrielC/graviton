package graviton.core.macros

import zio.test.*

object CaseClassFieldsSpec extends ZIOSpecDefault:

  private final case class Demo(a: Int, b: String)

  override def spec: Spec[TestEnvironment, Any] =
    suite("CaseClassFields")(
      test("names returns case class field names") {
        assertTrue(CaseClassFields.names[Demo] == List("a", "b"))
      }
    )
