package graviton.runtime.resilience

import zio.*
import zio.test.*

object CircuitBreakerSpec extends ZIOSpecDefault:

  override def spec =
    suite("CircuitBreaker")(
      test("allows calls in Closed state") {
        for
          cb     <- CircuitBreaker.make(maxFailures = 3, openFor = 1.second)
          result <- cb.protect(ZIO.succeed(42)).provideSomeLayer(ZLayer.succeed(Clock.ClockLive))
        yield assertTrue(result == 42)
      },
      test("counts failures and opens after threshold") {
        for
          cb <- CircuitBreaker.make(maxFailures = 2, openFor = 10.seconds)
          _  <- cb.protect(ZIO.fail("err1")).ignore.provideSomeLayer(ZLayer.succeed(Clock.ClockLive))
          _  <- cb.protect(ZIO.fail("err2")).ignore.provideSomeLayer(ZLayer.succeed(Clock.ClockLive))
          r  <- cb.protect(ZIO.succeed(1)).either.provideSomeLayer(ZLayer.succeed(Clock.ClockLive))
        yield assertTrue(r.isLeft)
      },
      test("rejects calls when Open with CircuitBreakerRejected") {
        for
          cb <- CircuitBreaker.make(maxFailures = 1, openFor = 10.seconds)
          _  <- cb.protect(ZIO.fail("trigger")).ignore.provideSomeLayer(ZLayer.succeed(Clock.ClockLive))
          r  <- cb.protect(ZIO.succeed(1)).either.provideSomeLayer(ZLayer.succeed(Clock.ClockLive))
        yield assertTrue(r.isLeft)
      },
      test("resets failure count on success in Closed") {
        for
          cb <- CircuitBreaker.make(maxFailures = 3, openFor = 10.seconds)
          _  <- cb.protect(ZIO.fail("err1")).ignore.provideSomeLayer(ZLayer.succeed(Clock.ClockLive))
          _  <- cb.protect(ZIO.fail("err2")).ignore.provideSomeLayer(ZLayer.succeed(Clock.ClockLive))
          _  <- cb.protect(ZIO.succeed("ok")).provideSomeLayer(ZLayer.succeed(Clock.ClockLive))
          _  <- cb.protect(ZIO.fail("err3")).ignore.provideSomeLayer(ZLayer.succeed(Clock.ClockLive))
          _  <- cb.protect(ZIO.fail("err4")).ignore.provideSomeLayer(ZLayer.succeed(Clock.ClockLive))
          r  <- cb.protect(ZIO.succeed(42)).provideSomeLayer(ZLayer.succeed(Clock.ClockLive))
        yield assertTrue(r == 42)
      },
      test("propagates the original error type on failure") {
        for
          cb <- CircuitBreaker.make(maxFailures = 10, openFor = 10.seconds)
          r  <- cb.protect(ZIO.fail("my error")).either.provideSomeLayer(ZLayer.succeed(Clock.ClockLive))
        yield assertTrue(r == Left("my error"))
      },
    )
