package graviton.runtime.constraints

import zio.*
import zio.test.*

object ThrottleSpec extends ZIOSpecDefault:
  private def take(throttle: Throttle, tokens: Long): UIO[Boolean] =
    ZIO.clockWith(clock => throttle.take(tokens).provideEnvironment(ZEnvironment[Clock](clock)))

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("Throttle")(
    test("the first live-time charge cannot overflow refill arithmetic") {
      for
        throttle <- Throttle.make(50L * 1024L * 1024L)
        allowed  <- throttle.take(1024L).provide(ZLayer.succeed(Clock.ClockLive))
      yield assertTrue(allowed)
    },
    test("refill saturates at capacity without multiplication overflow") {
      val maximum = Long.MaxValue / 2L
      for
        now     <- ZIO.clockWith(_.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS))
        state   <- Ref.make((0L, now))
        throttle = Throttle(state, maximum)
        _       <- TestClock.adjust(1.second)
        allowed <- take(throttle, maximum)
      yield assertTrue(allowed)
    },
    test("denied retries cannot credit the same elapsed interval twice") {
      for
        throttle <- Throttle.make(10L)
        consumed <- take(throttle, 10L)
        _        <- TestClock.adjust(500.millis)
        first    <- take(throttle, 10L)
        retry    <- take(throttle, 10L)
      yield assertTrue(consumed, !first, !retry)
    },
  )
