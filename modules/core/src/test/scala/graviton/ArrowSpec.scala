package graviton

import zio.Chunk
import zio.test.*

object ArrowSpec extends ZIOSpecDefault:
  def spec = suite("ArrowSpec")(
    test("composition inverts and records labels") {
      val xor = InvertibleArrow.lift[Chunk[Byte], Chunk[Byte]](
        label = "xor",
        inverse = "xor",
        "key" -> 0x0f
      )
      val rev = InvertibleArrow.lift[Chunk[Byte], Chunk[Byte]](
        label = "reverse",
        inverse = "reverse"
      )
      val arrow = xor >>> rev
      val labels = arrow.steps.map(_.label)
      val invLabels = arrow.invert.steps.map(_.label)
      assertTrue(
        labels == Chunk("xor", "reverse") && invLabels == Chunk(
          "reverse",
          "xor"
        )
      )
    }
  )
