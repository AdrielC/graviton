package graviton.integration.shardcake

import zio.*
import zio.test.*

object ShardcakeRegistrationSpec extends ZIOSpecDefault:
  private val rejected = new RuntimeException("pod health endpoint is not ready")

  def spec =
    suite("ShardcakeRegistrationSpec")(
      test("retries until manager persistence confirms registration using the test clock") {
        ZIO.scoped {
          for
            attempts <- Ref.make(0)
            fiber    <- ShardcakeRegistration
                          .scoped(
                            attempts.update(_ + 1),
                            attempts.get.map(_ >= 3),
                            ZIO.unit,
                            1.second,
                            10.seconds,
                          )
                          .fork
            _        <- ZIO.yieldNow
            _        <- TestClock.adjust(1.second)
            _        <- ZIO.yieldNow
            _        <- TestClock.adjust(1.second)
            _        <- fiber.join
            count    <- attempts.get
          yield assertTrue(count == 3)
        }
      },
      test("fails with the last registration error after the configured timeout") {
        ZIO.scoped {
          for
            attempts <- Ref.make(0)
            fiber    <- ShardcakeRegistration
                          .scoped(
                            attempts.update(_ + 1) *> ZIO.fail(rejected),
                            ZIO.succeed(false),
                            ZIO.unit,
                            1.second,
                            3.seconds,
                          )
                          .fork
            _        <- ZIO.yieldNow
            _        <- TestClock.adjust(1.second)
            _        <- ZIO.yieldNow
            _        <- TestClock.adjust(1.second)
            _        <- ZIO.yieldNow
            _        <- TestClock.adjust(1.second)
            exit     <- fiber.await
            count    <- attempts.get
          yield assertTrue(exit.causeOption.flatMap(_.failureOption).contains(rejected), count == 4)
        }
      },
      test("installs the unregister finalizer only after registration succeeds") {
        for
          unregistered <- Ref.make(false)
          _            <- ZIO.scoped(
                            ShardcakeRegistration.scoped(
                              ZIO.unit,
                              ZIO.succeed(true),
                              unregistered.set(true),
                              1.second,
                              3.seconds,
                            )
                          )
          closed       <- unregistered.get
        yield assertTrue(closed)
      },
      test("does not unregister when registration never succeeds") {
        for
          unregistered <- Ref.make(false)
          fiber        <- ZIO
                            .scoped(
                              ShardcakeRegistration.scoped(
                                ZIO.fail(rejected),
                                ZIO.succeed(false),
                                unregistered.set(true),
                                1.second,
                                1.second,
                              )
                            )
                            .fork
          _            <- ZIO.yieldNow
          _            <- TestClock.adjust(1.second)
          _            <- fiber.await
          closed       <- unregistered.get
        yield assertTrue(!closed)
      },
    )
