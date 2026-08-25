package graviton.shared.cas

import zio.test.*

object ContentAddressingJvmSpec extends ZIOSpecDefault:
  def spec = ContentAddressingContract.spec
