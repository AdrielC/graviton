package graviton.shared

import scala.scalajs.reflect.annotation.EnableReflectiveInstantiation
import zio.test.*

@EnableReflectiveInstantiation
object ApiJsSpec extends ZIOSpecDefault:
  def spec = ApiContract.spec
