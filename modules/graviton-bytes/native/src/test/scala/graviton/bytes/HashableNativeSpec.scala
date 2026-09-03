package graviton.bytes

import scala.scalanative.reflect.annotation.EnableReflectiveInstantiation
import zio.test.*

@EnableReflectiveInstantiation
object HashableNativeSpec extends ZIOSpecDefault:
  def spec = HashableContract.spec
