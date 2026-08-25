package graviton.security.jwt

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.{JWKSource, JWKSourceBuilder}
import com.nimbusds.jose.proc.{JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jose.util.DefaultResourceRetriever
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.{DefaultJWTClaimsVerifier, DefaultJWTProcessor}
import graviton.security.{CallerContext, JwtVerifier, SecurityConfig, SecurityError}
import zio.*

import java.net.URI
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** RS256 OIDC verifier backed by a rotating remote JWKS cache. */
object OidcJwtVerifier:

  private val RequiredClaims = Set("sub", "jti", "exp", "org_id").asJava

  def make(config: SecurityConfig): Task[JwtVerifier] =
    for
      issuer   <- required(config.oidcIssuer, "OIDC issuer")
      audience <- required(config.oidcAudience, "OIDC audience")
      jwksRaw  <- required(config.oidcJwksUri, "OIDC JWKS URI")
      jwksUri  <- ZIO
                    .fromTry(Try(URI.create(jwksRaw)))
                    .mapError(err => new IllegalArgumentException("Invalid OIDC JWKS URI", err))
      _        <- ZIO
                    .fail(new IllegalArgumentException("OIDC JWKS URI must use HTTPS"))
                    .unless(jwksUri.isAbsolute && jwksUri.getScheme.equalsIgnoreCase("https"))
      source   <- ZIO.attempt {
                    val cacheMillis   = math.max(60_000L, config.jwksCacheTtl.toMillis)
                    val refreshMillis = math.max(5_000L, cacheMillis / 10L)
                    val timeoutMillis = math.max(1000L, math.min(refreshMillis, 30000L)).toInt
                    val retriever     = DefaultResourceRetriever(timeoutMillis, timeoutMillis, 1024 * 1024)
                    JWKSourceBuilder
                      .create[SecurityContext](jwksUri.toURL, retriever)
                      .cache(cacheMillis, refreshMillis)
                      .retrying(true)
                      .rateLimited(true)
                      .build()
                  }
    yield fromJwkSource(source, issuer, audience, config.clockSkewSeconds)

  private[security] def fromJwkSource(
    source: JWKSource[SecurityContext],
    issuer: String,
    audience: String,
    clockSkewSeconds: Long,
  ): JwtVerifier =
    val processor = DefaultJWTProcessor[SecurityContext]()
    processor.setJWSKeySelector(JWSVerificationKeySelector(JWSAlgorithm.RS256, source))

    val expected = JWTClaimsSet.Builder().issuer(issuer).audience(audience).build()
    val verifier = DefaultJWTClaimsVerifier[SecurityContext](expected, RequiredClaims)
    verifier.setMaxClockSkew(math.max(0L, math.min(clockSkewSeconds, Int.MaxValue.toLong)).toInt)
    processor.setJWTClaimsSetVerifier(verifier)

    new JwtVerifier:
      override def verify(bearerToken: String, requestId: UUID): IO[SecurityError, CallerContext] =
        ZIO
          .attempt(processor.process(bearerToken, null))
          .mapError(err => SecurityError.Unauthenticated("JWT verification failed", Some(err)))
          .flatMap(claims => toContext(claims, requestId, clockSkewSeconds))

  private def toContext(
    claims: JWTClaimsSet,
    requestId: UUID,
    clockSkewSeconds: Long,
  ): IO[SecurityError, CallerContext] =
    for
      now <- Clock.instant
      ctx <- ZIO.fromEither(
               ClaimMapping.toContext(
                 ClaimMapping.Claims(
                   sub = Option(claims.getSubject),
                   orgId = stringClaim(claims, "org_id").orElse(stringClaim(claims, "https://graviton.io/org")),
                   principalId = stringClaim(claims, "principal_id"),
                   jti = Option(claims.getJWTID),
                   exp = Option(claims.getExpirationTime).map(_.toInstant.getEpochSecond),
                   nbf = Option(claims.getNotBeforeTime).map(_.toInstant.getEpochSecond),
                   scope = stringClaim(claims, "scope"),
                   capsMask = longClaim(claims, "caps"),
                 ),
                 requestId,
                 now.getEpochSecond,
                 clockSkewSeconds,
               )
             )
    yield ctx

  private def stringClaim(claims: JWTClaimsSet, name: String): Option[String] =
    Option(claims.getClaim(name)).collect { case value: String => value }

  private def longClaim(claims: JWTClaimsSet, name: String): Option[Long] =
    Option(claims.getClaim(name)).flatMap {
      case value: java.lang.Number => Some(value.longValue())
      case value: String           => value.toLongOption
      case _                       => None
    }

  private def required(value: Option[String], label: String): Task[String] =
    ZIO.fromOption(value.filter(_.nonEmpty)).orElseFail(new IllegalArgumentException(s"$label is required"))
