package graviton.security

import zio.*

import java.util.UUID

/**
 * Verifies a bearer token and yields a [[CallerContext]]. The production
 * implementation validates an OIDC JWT against a JWKS endpoint; other
 * implementations exist for testing and for deployments that front Graviton
 * with a trusted gateway that has already verified the token.
 */
trait JwtVerifier:
  def verify(bearerToken: String, requestId: UUID): IO[SecurityError, CallerContext]

object JwtVerifier:

  /**
   * A verifier that accepts every token and returns a fixed context.
   *
   * Only use from tests or local development with `GRAVITON_SEC_ENABLED=false`.
   * Wiring it into production should be a loud compile-time choice, not the
   * default.
   */
  def static(ctx: CallerContext): JwtVerifier =
    new JwtVerifier:
      def verify(bearerToken: String, requestId: UUID): IO[SecurityError, CallerContext] =
        ZIO.succeed(ctx.copy(requestId = requestId))

  /**
   * Rejects every request. Used as the safe default when
   * `SecurityConfig.enabled = true` but no live verifier has been wired.
   */
  val denyAll: JwtVerifier =
    new JwtVerifier:
      def verify(bearerToken: String, requestId: UUID): IO[SecurityError, CallerContext] =
        ZIO.fail(SecurityError.Unauthenticated("no JwtVerifier implementation bound"))
