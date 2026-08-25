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
    }
  )

end RateLimiterSpec
