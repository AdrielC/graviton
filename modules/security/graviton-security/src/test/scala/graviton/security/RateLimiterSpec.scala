package graviton.security

import zio.*
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant
import java.util.UUID

object RateLimiterSpec extends ZIOSpecDefault:

  private def mkContext: CallerContext =
    CallerContext(
      orgId = UUID.randomUUID(),
      principalId = UUID.randomUUID(),
      capabilities = CapabilitySet.empty,
      jti = "t",
      tokenExpiresAt = Instant.parse("2099-01-01T00:00:00Z"),
      requestId = UUID.randomUUID(),
    )

  private val smallConfig = SecurityConfig.Default.copy(
    enabled = true,
    oidcIssuer = Some("https://issuer.example"),
    oidcAudience = Some("graviton"),
    rateLimitPerPrincipalPerSec = 3L,
    rateLimitUploadBytesPerSec = 1024L,
    rateLimitDownloadBytesPerSec = 1024L,
  )

  def spec: Spec[TestEnvironment & zio.Scope, Any] = suite("RateLimiter")(
    test("allows up to the per-second budget then denies") {
      val ctx = mkContext
      for
        res         <- ZIO
                         .serviceWithZIO[RateLimiter] { limiter =>
                           CallerContext.scopedWith(ctx) {
                             for
                               a <- limiter.check(RateLimiter.Kind.Request, 1L).exit
                               b <- limiter.check(RateLimiter.Kind.Request, 1L).exit
                               c <- limiter.check(RateLimiter.Kind.Request, 1L).exit
                               d <- limiter.check(RateLimiter.Kind.Request, 1L).exit
                             yield (a, b, c, d)
                           }
                         }
                         .provide(ZLayer.succeed(smallConfig), RateLimiter.live)
        (a, b, c, d) = res
      yield assert(a)(succeeds(isUnit)) &&
        assert(b)(succeeds(isUnit)) &&
        assert(c)(succeeds(isUnit)) &&
        assert(d)(fails(isSubtype[SecurityError.RateLimited](anything)))
    },
    test("bounds principal cardinality and fails closed while entries are active") {
      val first  = mkContext
      val second = mkContext
      val config = smallConfig.copy(rateLimitMaximumPrincipals = 1, rateLimitIdleTtl = 1.hour)

      for
        limiter  <- ZIO.service[RateLimiter].provide(ZLayer.succeed(config), RateLimiter.live)
        accepted <- CallerContext.scopedWith(first)(limiter.check(RateLimiter.Kind.Request, 1L).exit)
        rejected <- CallerContext.scopedWith(second)(limiter.check(RateLimiter.Kind.Request, 1L).exit)
      yield assert(accepted)(succeeds(isUnit)) &&
        assert(rejected)(fails(isSubtype[SecurityError.RateLimited](anything)))
    },
    test("evicts only an expired principal entry") {
      val first  = mkContext
      val second = mkContext
      val config = smallConfig.copy(rateLimitMaximumPrincipals = 1, rateLimitIdleTtl = 1.second)

      for
        limiter  <- ZIO.service[RateLimiter].provide(ZLayer.succeed(config), RateLimiter.live)
        _        <- CallerContext.scopedWith(first)(limiter.check(RateLimiter.Kind.Request, 1L))
        _        <- TestClock.adjust(1.second)
        accepted <- CallerContext.scopedWith(second)(limiter.check(RateLimiter.Kind.Request, 1L).exit)
      yield assert(accepted)(succeeds(isUnit))
    },
    test("rejects non-positive charges without increasing a bucket") {
      val ctx = mkContext
      for
        limiter  <- ZIO.service[RateLimiter].provide(ZLayer.succeed(smallConfig), RateLimiter.live)
        zero     <- CallerContext.scopedWith(ctx)(limiter.check(RateLimiter.Kind.Request, 0L).exit)
        negative <- CallerContext.scopedWith(ctx)(limiter.check(RateLimiter.Kind.Request, -1L).exit)
      yield assert(zero)(fails(isSubtype[SecurityError.RateLimited](anything))) &&
        assert(negative)(fails(isSubtype[SecurityError.RateLimited](anything)))
    },
  )

end RateLimiterSpec
