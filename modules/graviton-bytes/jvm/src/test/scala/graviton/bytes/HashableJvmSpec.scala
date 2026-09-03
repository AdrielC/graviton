package graviton.bytes

import zio.test.*

object HashableJvmSpec extends ZIOSpecDefault:
  def spec = HashableContract.spec
