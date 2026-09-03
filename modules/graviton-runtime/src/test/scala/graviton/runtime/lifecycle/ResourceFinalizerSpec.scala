package graviton.runtime.lifecycle

import zio.ZIO
import zio.test.*

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

object ResourceFinalizerSpec extends ZIOSpecDefault:
  override def spec = suite("ResourceFinalizer")(
    test("reports cleanup failure without turning scope closure into a defect") {
      for
        closed <- ZIO.succeed(new AtomicBoolean(false))
        exit   <- ZIO
                    .scoped(
                      ZIO.acquireRelease(ZIO.unit)(_ =>
                        ResourceFinalizer.closeBlocking("test resource") {
                          closed.set(true)
                          throw new IOException("close failed")
                        }
                      )
                    )
                    .exit
        value  <- ZIO.succeed(closed.get())
      yield assertTrue(exit.isSuccess, value)
    }
  )
