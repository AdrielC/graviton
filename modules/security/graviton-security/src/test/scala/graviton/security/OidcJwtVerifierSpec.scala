package graviton.security

import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import graviton.security.jwt.OidcJwtVerifier
import zio.*
import zio.test.*

import java.time.Instant
import java.util.{Date, UUID}

object OidcJwtVerifierSpec extends ZIOSpecDefault:

  private val issuer   = "https://issuer.example"
  private val audience = "graviton"

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("OidcJwtVerifier")(
      test("accepts an RS256 token from the configured issuer and audience") {
        for
          fixture <- makeToken(issuer, audience, keyId = "active")
          verifier = OidcJwtVerifier.fromJwkSource(fixture.source, issuer, audience, clockSkewSeconds = 5L)
          ctx     <- verifier.verify(fixture.token, UUID.randomUUID())
        yield assertTrue(
          ctx.orgId == fixture.orgId,
          ctx.principalId == fixture.principalId,
          ctx.capabilities.contains(Capability.BlobRead),
        )
      },
      test("rejects a token for another audience") {
        for
          fixture <- makeToken(issuer, "another-service", keyId = "active")
          verifier = OidcJwtVerifier.fromJwkSource(fixture.source, issuer, audience, clockSkewSeconds = 5L)
          result  <- verifier.verify(fixture.token, UUID.randomUUID()).exit
        yield assertTrue(result.isFailure)
      },
      test("rejects an unknown signing key") {
        for
          trusted <- makeToken(issuer, audience, keyId = "trusted")
          other   <- makeToken(issuer, audience, keyId = "other")
          verifier = OidcJwtVerifier.fromJwkSource(trusted.source, issuer, audience, clockSkewSeconds = 5L)
          result  <- verifier.verify(other.token, UUID.randomUUID()).exit
        yield assertTrue(result.isFailure)
      },
    )

  private final case class TokenFixture(
    token: String,
    source: ImmutableJWKSet[SecurityContext],
    orgId: UUID,
    principalId: UUID,
  )

  private def makeToken(tokenIssuer: String, tokenAudience: String, keyId: String): Task[TokenFixture] =
    ZIO.attempt {
      val key         = RSAKeyGenerator(2048).keyID(keyId).generate()
      val orgId       = UUID.randomUUID()
      val principalId = UUID.randomUUID()
      val now         = Instant.now()
      val claims      = JWTClaimsSet
        .Builder()
        .issuer(tokenIssuer)
        .audience(tokenAudience)
        .subject(principalId.toString)
        .jwtID(UUID.randomUUID().toString)
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(300L)))
        .claim("org_id", orgId.toString)
        .claim("principal_id", principalId.toString)
        .claim("scope", "blob.read")
        .build()
      val jwt         = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), claims)
      jwt.sign(RSASSASigner(key))
      TokenFixture(
        token = jwt.serialize(),
        source = ImmutableJWKSet(JWKSet(key.toPublicJWK)),
        orgId = orgId,
        principalId = principalId,
      )
    }
