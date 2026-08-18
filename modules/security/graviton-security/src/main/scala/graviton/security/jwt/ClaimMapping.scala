package graviton.security.jwt

import graviton.security.{CallerContext, Capability, CapabilitySet, SecurityError}

import java.time.Instant
import java.util.UUID

import scala.util.Try

/**
 * Maps a decoded JWT claim set to a [[CallerContext]]. Kept pure so it can
 * be unit-tested without any network / crypto layer.
 *
 * Expected claims (OIDC-compatible):
 *   - `iss`  : token issuer (validated upstream against configured issuer)
 *   - `aud`  : audience (validated upstream against configured audience)
 *   - `exp`  : expiration epoch seconds
 *   - `nbf`  : not-before epoch seconds (optional)
 *   - `jti`  : unique token id
 *   - `sub`  : principal UUID (or `principal_id` claim)
 *   - `org_id` or `https://graviton.io/org` : org UUID
 *   - `scope` (string) or `caps` (number) : capability bits
 */
object ClaimMapping:

  final case class Claims(
    sub: Option[String],
    orgId: Option[String],
    principalId: Option[String],
    jti: Option[String],
    exp: Option[Long],
    nbf: Option[Long],
    scope: Option[String],
    capsMask: Option[Long],
  )

  def toContext(
    claims: Claims,
    requestId: UUID,
    nowEpochSeconds: Long,
    clockSkewSeconds: Long,
  ): Either[SecurityError, CallerContext] =
    for
      exp  <- claims.exp.toRight(SecurityError.Unauthenticated("token missing `exp`"))
      _    <- Either.cond(
                exp + clockSkewSeconds >= nowEpochSeconds,
                (),
                SecurityError.Unauthenticated("token expired"),
              )
      _    <- claims.nbf match
                case Some(nbf) if nbf - clockSkewSeconds > nowEpochSeconds =>
                  Left(SecurityError.Unauthenticated("token not yet valid"))
                case _                                                     =>
                  Right(())
      jti  <- claims.jti.toRight(SecurityError.Unauthenticated("token missing `jti`"))
      org  <- claims.orgId.toRight(SecurityError.Unauthenticated("token missing `org_id` claim"))
      orgU <- parseUuid(org, "org_id")
      pid  <- claims.principalId.orElse(claims.sub).toRight(SecurityError.Unauthenticated("token missing `sub`/`principal_id`"))
      pidU <- parseUuid(pid, "sub/principal_id")
    yield
      val fromScope = claims.scope.map(Capability.fromScopeString).getOrElse(CapabilitySet.empty)
      val fromMask  = claims.capsMask.map(CapabilitySet.fromMask).getOrElse(CapabilitySet.empty)
      CallerContext(
        orgId = orgU,
        principalId = pidU,
        capabilities = fromScope ++ fromMask,
        jti = jti,
        tokenExpiresAt = Instant.ofEpochSecond(exp),
        requestId = requestId,
      )

  private def parseUuid(raw: String, field: String): Either[SecurityError, UUID] =
    Try(UUID.fromString(raw)).toEither.left.map(err => SecurityError.Unauthenticated(s"claim `$field` is not a UUID", Some(err)))
