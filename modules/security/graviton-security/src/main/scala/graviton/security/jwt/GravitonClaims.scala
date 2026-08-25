package graviton.security.jwt

import java.util.UUID

import scala.util.Try

/**
 * Graviton-specific JWT payload. Mirrors the fields [[ClaimMapping]] folds
 * into a [[graviton.security.CallerContext]].
 *
 * Kept as plain Scala types (String, Option, Long) so the codec can be
 * supplied by whichever JWT library is wired in (zio-jwt, jwt-scala, etc.).
 */
final case class GravitonClaims(
  sub: String,
  jti: String,
  exp: Long,
  nbf: Option[Long],
  iss: String,
  aud: String,
  orgId: String,
  principalId: Option[String],
  scope: Option[String],
  caps: Option[Long],
):
  def toMappingClaims: ClaimMapping.Claims =
    ClaimMapping.Claims(
      sub = Some(sub),
      orgId = Some(orgId),
      principalId = principalId,
      jti = Some(jti),
      exp = Some(exp),
      nbf = nbf,
      scope = scope,
      capsMask = caps,
    )

object GravitonClaims:

  def fromKnownClaims(
    sub: String,
    jti: String,
    exp: Long,
    nbf: Option[Long],
    iss: String,
    aud: String,
    extra: Map[String, String],
  ): Either[String, GravitonClaims] =
    extra.get("org_id") match
      case None        => Left("claim `org_id` missing")
      case Some(value) =>
        Try(UUID.fromString(value)).toEither.left.map(err => s"claim `org_id` not a UUID: ${err.getMessage}").map { _ =>
          GravitonClaims(
            sub = sub,
            jti = jti,
            exp = exp,
            nbf = nbf,
            iss = iss,
            aud = aud,
            orgId = value,
            principalId = extra.get("principal_id"),
            scope = extra.get("scope"),
            caps = extra.get("caps").flatMap(s => Try(s.toLong).toOption),
          )
        }
