package graviton

import zio.*
import zio.test.*

object ArrowSpec extends ZIOSpecDefault:
  def spec = suite("ArrowSpec")(
    test("composition runs and inverts with introspection") {
      val xor = InvertibleArrow.lift[Chunk[Byte], Chunk[Byte]](
        label = "xor",
        forward = _.map(b => (b ^ 0x0f).toByte),
        backward = _.map(b => (b ^ 0x0f).toByte),
        "key" -> 0x0f
      )
      val rev = InvertibleArrow.lift[Chunk[Byte], Chunk[Byte]](
        label = "reverse",
        forward = _.reverse,
        backward = _.reverse
      )
      val arrow = xor >>> rev
      val data = Chunk.fromArray("zio".getBytes("UTF-8"))
      val enc = arrow.run(data)
      val dec = arrow.invert.run(enc)
      val labels = arrow.steps.map(_.label)
      val invLabels = arrow.invert.steps.map(_.label)
      assertTrue(
        dec == data && labels == Chunk("xor", "reverse") && invLabels == Chunk(
          "reverse",
          "xor"
        )
      )
    }
  )
