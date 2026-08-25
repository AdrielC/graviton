package graviton.streams

import zio.*
import zio.stream.ZStream
import zio.test.*

object BoundedByteStreamSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("BoundedByteStream")(
      test("control-plane overflow pulls at most limit plus one byte") {
        for
          pulled <- Ref.make(0L)
          source  = ZStream.repeatZIO(pulled.updateAndGet(_ + 1L).as(0.toByte))
          exit   <- BoundedByteStream.collectControlPlane(source).exit
          count  <- pulled.get
        yield assertTrue(
          exit == Exit.fail(BoundedByteStream.LimitExceeded(BoundedByteStream.MaxControlPlaneBytes.toLong)),
          count == BoundedByteStream.MaxControlPlaneBytes.toLong + 1L,
        )
      },
      test("block collection rejects empty input in the refined domain type") {
        for exit <- BoundedByteStream.collectBlock(ZStream.empty).exit
        yield assertTrue(exit.isFailure)
      },
    )
