package graviton.bytes

import scala.scalajs.reflect.annotation.EnableReflectiveInstantiation
import zio.test.*

@EnableReflectiveInstantiation
object HashableJsSpec extends ZIOSpecDefault:
  def spec = HashableContract.spec
