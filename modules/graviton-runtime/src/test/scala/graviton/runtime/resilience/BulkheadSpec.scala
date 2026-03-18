package graviton.runtime.resilience

import zio.*
import zio.test.*

object BulkheadSpec extends ZIOSpecDefault:

  override def spec =
    suite("Bulkhead")(
      test("allows calls up to max concurrency") {
        for
          bh  <- Bulkhead.make(2)
          ref <- Ref.make(0)
          _   <- ZIO.foreachParDiscard(1 to 5) { _ =>
                   bh.withPermit(ref.update(_ + 1))
                 }
          n   <- ref.get
        yield assertTrue(n == 5)
      },
      test("rejects zero maxConcurrent") {
        for r <- Bulkhead.make(0).exit
        yield assertTrue(r.isFailure)
      },
      test("rejects negative maxConcurrent") {
        for r <- Bulkhead.make(-1).exit
        yield assertTrue(r.isFailure)
      },
      test("accepts maxConcurrent of 1") {
        for
          bh  <- Bulkhead.make(1)
          res <- bh.withPermit(ZIO.succeed(42))
        yield assertTrue(res == 42)
      },
      test("limits actual concurrency") {
        for
          bh      <- Bulkhead.make(2)
          maxSeen <- Ref.make(0)
          current <- Ref.make(0)
          latch   <- Promise.make[Nothing, Unit]
          _       <- ZIO.foreachParDiscard(1 to 10) { _ =>
                       bh.withPermit {
                         for
                           n <- current.updateAndGet(_ + 1)
                           _ <- maxSeen.update(m => math.max(m, n))
                           _ <- ZIO.yieldNow
                           _ <- current.update(_ - 1)
                         yield ()
                       }
                     }
          peak    <- maxSeen.get
        yield assertTrue(peak <= 2)
      },
    )
