package graviton.security.jwt

import graviton.security.{CallerContext, JwtVerifier, SecurityConfig, SecurityError}
import zio.*

import java.util.UUID

/**
 * Thin adapter over the `io.github.arashi01` zio-jwt `JwtValidator` service.
 * The library handles JWKS fetching, background rotation, stampede
 * prevention, algorithm allow-listing, `iss`/`aud`/`exp`/`nbf`/`jti`
 * enforcement; we translate the decoded [[GravitonClaims]] into a
 * [[CallerContext]] via the pure [[ClaimMapping]].
 *
 * NOTE: the zio-jwt dependency is added in `build.sbt` for this module.
 * The actual `JwtValidator` wiring (live layer, JwksProviderConfig, etc.)
 * lives in `graviton-server` where HTTP client + Scope are already in
 * scope; this file only defines how a decoded claims object becomes a
 * CallerContext.
 */
object ZioJwtVerifier:

  /**
   * Builds a verifier from a function that decodes a token into
   * [[GravitonClaims]] — typically supplied by
   * `JwtValidator.validate[GravitonClaims]` at wiring time.
   *
   * Keeping the verifier parameterised by this function keeps
   * `graviton-security` free of a direct dependency on the specific JWT
   * library version; the concrete binding is in `graviton-server`.
   */
  def fromDecoder(
    decode: String => IO[SecurityError, GravitonClaims],
    clockSkewSeconds: Long,
  ): JwtVerifier =
    new JwtVerifier:
      def verify(bearerToken: String, requestId: UUID): IO[SecurityError, CallerContext] =
        for
          claims <- decode(bearerToken)
          now    <- ZIO.clockWith(_.instant)
          ctx    <- ZIO.fromEither(
                      ClaimMapping.toContext(
                        claims.toMappingClaims,
                        requestId,
                        now.getEpochSecond,
                        clockSkewSeconds,
                      )
                    )
        yield ctx

  /**
   * Derives clock skew from [[SecurityConfig]] and combines with a decoder.
   * The decoder is the piece that needs the live `JwtValidator` service;
   * `graviton-server` supplies it when it wires everything together.
   */
  def layerFromDecoder(
    decode: String => IO[SecurityError, GravitonClaims]
  ): URLayer[SecurityConfig, JwtVerifier] =
    ZLayer.fromZIO {
      ZIO.serviceWith[SecurityConfig](cfg => fromDecoder(decode, cfg.clockSkewSeconds))
    }
