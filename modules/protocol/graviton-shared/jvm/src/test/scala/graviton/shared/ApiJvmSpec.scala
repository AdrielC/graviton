package graviton.shared

import zio.test.*

object ApiJvmSpec extends ZIOSpecDefault:
  def spec = ApiContract.spec
