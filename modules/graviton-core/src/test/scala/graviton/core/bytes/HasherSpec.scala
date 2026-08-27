package graviton.core.bytes

import zio.test.*

object HasherSpec extends ZIOSpecDefault:

  override def spec: Spec[Any, Any] =
    suite("Hasher")(
      test("reset clears both digest state and the tracked input size") {
        val hasher = Hasher.systemDefault.toOption.get
        val _      = hasher.update("before-reset")
        val _      = hasher.digest

        hasher.reset
        val sizeAfterReset = hasher.inputSize
        val _              = hasher.update("x")
        val bits           = hasher.digestKeyBits.toOption.get

        assertTrue(
          sizeAfterReset == 0L,
          bits.size == 1L,
        )
      }
    )
