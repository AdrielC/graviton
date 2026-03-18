package graviton.runtime.constraints

import zio.*
import zio.test.*

import java.nio.file.Files

object ConstraintsSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("Constraints")(
      quotaSpec,
      semaphoreLimitSpec,
      spillPolicySpec,
    )

  // ---------------------------------------------------------------------------
  // Quota
  // ---------------------------------------------------------------------------

  private val quotaSpec =
    suite("Quota")(
      test("allows take while under limit") {
        for
          quota  <- Quota.make(limit = 10L)
          result <- quota.take(5L)
        yield assertTrue(result)
      },
      test("allows take that reaches exactly the limit") {
        for
          quota  <- Quota.make(limit = 10L)
          result <- quota.take(10L)
        yield assertTrue(result)
      },
      test("rejects take that would exceed the limit") {
        for
          quota  <- Quota.make(limit = 10L)
          _      <- quota.take(8L)
          result <- quota.take(3L)
        yield assertTrue(!result)
      },
      test("accumulates usage across multiple successful takes") {
        for
          quota <- Quota.make(limit = 100L)
          r1    <- quota.take(30L)
          r2    <- quota.take(30L)
          r3    <- quota.take(40L)
          r4    <- quota.take(1L)
        yield assertTrue(r1, r2, r3, !r4)
      },
      test("does not update state on rejected take") {
        for
          quota  <- Quota.make(limit = 10L)
          _      <- quota.take(8L)
          _      <- quota.take(5L) // rejected: 8+5=13 > 10
          result <- quota.take(2L) // should succeed: 8+2=10 <= 10
        yield assertTrue(result)
      },
      test("allows zero-byte take") {
        for
          quota  <- Quota.make(limit = 10L)
          result <- quota.take(0L)
        yield assertTrue(result)
      },
    )

  // ---------------------------------------------------------------------------
  // SemaphoreLimit
  // ---------------------------------------------------------------------------

  private val semaphoreLimitSpec =
    suite("SemaphoreLimit")(
      test("allows a single-permit operation to complete") {
        for
          limit  <- SemaphoreLimit.make(1L)
          result <- limit.withPermit(ZIO.succeed(42))
        yield assertTrue(result == 42)
      },
      test("allows multiple sequential operations under single permit") {
        for
          limit   <- SemaphoreLimit.make(1L)
          results <- ZIO.foreach(1 to 5)(i => limit.withPermit(ZIO.succeed(i)))
        yield assertTrue(results == List(1, 2, 3, 4, 5))
      },
      test("limits parallelism: at most N tasks run concurrently") {
        for
          limit   <- SemaphoreLimit.make(2L)
          counter <- Ref.make(0)
          maxRef  <- Ref.make(0)
          // 10 tasks each: increment, yield (allow other fibers to run), observe max, decrement.
          // yieldNow avoids the TestClock hang that ZIO.sleep would cause.
          _       <- ZIO.foreachPar(1 to 10) { _ =>
                       limit.withPermit(
                         for
                           cur <- counter.updateAndGet(_ + 1)
                           _   <- maxRef.update(m => m.max(cur))
                           _   <- ZIO.yieldNow
                           _   <- counter.update(_ - 1)
                         yield ()
                       )
                     }
          maxSeen <- maxRef.get
        yield assertTrue(maxSeen <= 2)
      },
      test("propagates errors without releasing deadlock") {
        for
          limit  <- SemaphoreLimit.make(1L)
          result <- limit.withPermit(ZIO.fail(new RuntimeException("boom"))).exit
          // permit must be released: subsequent acquire must succeed
          next   <- limit.withPermit(ZIO.succeed("ok"))
        yield assertTrue(result.isFailure, next == "ok")
      },
    )

  // ---------------------------------------------------------------------------
  // SpillPolicy
  // ---------------------------------------------------------------------------

  private val spillPolicySpec =
    suite("SpillPolicy")(
      test("allocates a temp directory that exists") {
        ZIO.acquireReleaseWith(
          ZIO.attemptBlocking(Files.createTempDirectory("graviton-spill-root-"))
        )(dir =>
          ZIO
            .attemptBlocking(
              java.nio.file.Files
                .walk(dir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach { p =>
                  val _ = java.nio.file.Files.deleteIfExists(p)
                }
            )
            .orDie
        ) { root =>
          ZIO.scoped {
            val policy = SpillPolicy(root)
            for
              handle <- policy.allocate("test-")
              exists <- ZIO.attemptBlocking(Files.exists(handle.path))
            yield assertTrue(exists)
          }
        }
      },
      test("two allocations produce distinct directories") {
        ZIO.acquireReleaseWith(
          ZIO.attemptBlocking(Files.createTempDirectory("graviton-spill-root-"))
        )(dir =>
          ZIO
            .attemptBlocking(
              java.nio.file.Files
                .walk(dir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach { p =>
                  val _ = java.nio.file.Files.deleteIfExists(p)
                }
            )
            .orDie
        ) { root =>
          ZIO.scoped {
            val policy = SpillPolicy(root)
            for
              h1 <- policy.allocate("spill-")
              h2 <- policy.allocate("spill-")
            yield assertTrue(h1.path != h2.path)
          }
        }
      },
    )
