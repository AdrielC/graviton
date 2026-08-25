package graviton.shared.cas

import scala.scalajs.reflect.annotation.EnableReflectiveInstantiation
import zio.test.*

@EnableReflectiveInstantiation
object ContentAddressingJsSpec extends ZIOSpecDefault:
  def spec = ContentAddressingContract.spec
